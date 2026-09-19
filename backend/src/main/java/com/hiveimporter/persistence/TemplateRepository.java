package com.hiveimporter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiveimporter.api.ApiException;
import com.hiveimporter.api.ApiModels.ImportReport;
import com.hiveimporter.api.ApiModels.TemplateComment;
import com.hiveimporter.api.ApiModels.TemplateCounts;
import com.hiveimporter.api.ApiModels.TemplateDetail;
import com.hiveimporter.api.ApiModels.TemplateItem;
import com.hiveimporter.api.ApiModels.TemplateSection;
import com.hiveimporter.api.ApiModels.TemplateSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TemplateRepository {
    private static final TypeReference<LinkedHashMap<String, String>> METADATA_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TemplateRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<String> workspaceForTokenHash(String hash) {
        return jdbc.query("SELECT id FROM workspaces WHERE token_hash = ?",
                (rs, row) -> rs.getString("id"), hash).stream().findFirst();
    }

    public void lockWorkspaceQuota(int maximum) {
        jdbc.queryForObject("SELECT id FROM demo_quota_guard WHERE id = 1 FOR UPDATE", Integer.class);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM workspaces", Long.class);
        if (count != null && count >= maximum) {
            throw ApiException.limit("This demo has reached its workspace capacity. Please contact the demo maintainer.");
        }
    }

    public void createWorkspace(String id, String tokenHash, Instant createdAt) {
        jdbc.update("INSERT INTO workspaces (id, token_hash, created_at) VALUES (?, ?, ?)",
                id, tokenHash, utc(createdAt));
    }

    public void checkTemplateCapacity(String workspaceId, int newComments, int maximumTemplates, int maximumComments) {
        var workspace = jdbc.query("SELECT id FROM workspaces WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString("id"), workspaceId);
        if (workspace.isEmpty()) {
            throw ApiException.unauthorized();
        }
        var totals = jdbc.queryForMap(
                "SELECT COUNT(*) AS templates, COALESCE(SUM(comment_count), 0) AS comments FROM templates WHERE workspace_id = ?",
                workspaceId);
        if (((Number) totals.get("templates")).longValue() >= maximumTemplates
                || ((Number) totals.get("comments")).longValue() + newComments > maximumComments) {
            throw ApiException.limit("Workspace capacity reached: at most " + maximumTemplates
                    + " templates and " + maximumComments + " comments are allowed in this demo.");
        }
    }

    public List<TemplateSummary> list(String workspaceId) {
        return jdbc.query("SELECT * FROM templates WHERE workspace_id = ? ORDER BY updated_at DESC, id",
                (rs, row) -> summary(rs), workspaceId);
    }

    public void requireOwned(String workspaceId, String templateId) {
        if (jdbc.query("SELECT id FROM templates WHERE id = ? AND workspace_id = ?",
                (rs, row) -> rs.getString("id"), templateId, workspaceId).isEmpty()) {
            throw ApiException.notFound();
        }
    }

    public TemplateDetail find(String workspaceId, String templateId, boolean forUpdate) {
        var roots = jdbc.query("SELECT * FROM templates WHERE id = ? AND workspace_id = ?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                (rs, row) -> new TemplateRoot(summary(rs), report(rs.getString("import_report"))),
                templateId, workspaceId);
        if (roots.isEmpty()) {
            throw ApiException.notFound();
        }
        var sections = jdbc.query("""
                        SELECT s.id, s.name, s.position
                        FROM template_sections s JOIN templates t ON t.id = s.template_id
                        WHERE t.id = ? AND t.workspace_id = ? ORDER BY s.position
                        """,
                (rs, row) -> new SectionRow(rs.getString("id"), rs.getString("name"), rs.getInt("position")),
                templateId, workspaceId);
        var items = jdbc.query("""
                        SELECT i.id, i.name, i.position, i.section_id
                        FROM template_items i JOIN template_sections s ON s.id = i.section_id
                        JOIN templates t ON t.id = s.template_id
                        WHERE t.id = ? AND t.workspace_id = ? ORDER BY s.position, i.position
                        """,
                (rs, row) -> new ItemRow(rs.getString("id"), rs.getString("name"),
                        rs.getInt("position"), rs.getString("section_id")), templateId, workspaceId);
        var comments = jdbc.query("""
                        SELECT c.* FROM template_comments c JOIN template_items i ON i.id = c.item_id
                        JOIN template_sections s ON s.id = i.section_id JOIN templates t ON t.id = s.template_id
                        WHERE t.id = ? AND t.workspace_id = ? ORDER BY s.position, i.position, c.position
                        """,
                (rs, row) -> new CommentRow(rs.getString("item_id"), new TemplateComment(
                        rs.getString("id"), rs.getString("name"), rs.getInt("position"), rs.getString("comment_type"),
                        rs.getString("content_html"), rs.getString("preview_html"), rs.getString("original_html"),
                        rs.getInt("source_row"), rs.getString("source_sheet"), metadata(rs.getString("metadata")))),
                templateId, workspaceId);
        var commentsByItem = new HashMap<String, List<TemplateComment>>();
        comments.forEach(row -> commentsByItem.computeIfAbsent(row.itemId(), ignored -> new ArrayList<>()).add(row.comment()));
        var itemsBySection = new HashMap<String, List<TemplateItem>>();
        items.forEach(row -> itemsBySection.computeIfAbsent(row.sectionId(), ignored -> new ArrayList<>())
                .add(new TemplateItem(row.id(), row.name(), row.position(),
                        commentsByItem.getOrDefault(row.id(), List.of()))));
        var tree = sections.stream().map(row -> new TemplateSection(row.id(), row.name(), row.position(),
                itemsBySection.getOrDefault(row.id(), List.of()))).toList();
        var root = roots.getFirst();
        var summary = root.summary();
        return new TemplateDetail(summary.id(), summary.name(), summary.version(), summary.sourceFileName(),
                summary.sourceSha256(), summary.createdAt(), summary.updatedAt(), summary.duplicateOf(),
                summary.counts(), summary.warningCount(), root.report(), tree);
    }

    public void insert(String workspaceId, TemplateDetail template) {
        jdbc.update("""
                        INSERT INTO templates (id, workspace_id, name, version, source_file_name, source_sha256,
                        created_at, updated_at, duplicate_of, section_count, item_count, comment_count, warning_count, import_report)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                template.id(), workspaceId, template.name(), template.version(), template.sourceFileName(),
                template.sourceSha256(), utc(template.createdAt()), utc(template.updatedAt()), template.duplicateOf(),
                template.counts().sections(), template.counts().items(), template.counts().comments(),
                template.warningCount(), encode(template.importReport()));
        jdbc.batchUpdate("INSERT INTO template_sections (id, template_id, name, position) VALUES (?, ?, ?, ?)",
                template.sections(), 200, (statement, section) -> {
                    statement.setString(1, section.id());
                    statement.setString(2, template.id());
                    statement.setString(3, section.name());
                    statement.setInt(4, section.position());
                });
        var items = new ArrayList<ItemInsert>();
        var comments = new ArrayList<CommentInsert>();
        template.sections().forEach(section -> section.items().forEach(item -> {
            items.add(new ItemInsert(section.id(), item));
            item.comments().forEach(comment -> comments.add(new CommentInsert(item.id(), comment)));
        }));
        jdbc.batchUpdate("INSERT INTO template_items (id, section_id, name, position) VALUES (?, ?, ?, ?)",
                items, 200, (statement, entry) -> {
                    statement.setString(1, entry.item().id());
                    statement.setString(2, entry.sectionId());
                    statement.setString(3, entry.item().name());
                    statement.setInt(4, entry.item().position());
                });
        jdbc.batchUpdate("""
                        INSERT INTO template_comments (id, item_id, name, position, comment_type, content_html,
                        preview_html, original_html, source_row, source_sheet, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                comments, 100, (statement, entry) -> {
                    var comment = entry.comment();
                    statement.setString(1, comment.id());
                    statement.setString(2, entry.itemId());
                    statement.setString(3, comment.name());
                    statement.setInt(4, comment.position());
                    statement.setString(5, comment.type());
                    statement.setString(6, comment.contentHtml());
                    statement.setString(7, comment.previewHtml());
                    statement.setString(8, comment.originalHtml());
                    statement.setInt(9, comment.sourceRow());
                    statement.setString(10, comment.sourceSheet());
                    statement.setString(11, encode(comment.metadata()));
                });
    }

    public void update(String workspaceId, TemplateDetail before, TemplateDetail after) {
        int updated = jdbc.update("""
                        UPDATE templates SET name = ?, version = ?, updated_at = ?, import_report = ?, warning_count = ?
                        WHERE id = ? AND workspace_id = ? AND version = ?
                        """,
                after.name(), after.version(), utc(after.updatedAt()), encode(after.importReport()),
                after.warningCount(), before.id(), workspaceId, before.version());
        if (updated != 1) {
            throw ApiException.conflict();
        }
        var sections = new ArrayList<TemplateSection>();
        var items = new ArrayList<ItemInsert>();
        var comments = new ArrayList<CommentInsert>();
        for (int sectionIndex = 0; sectionIndex < before.sections().size(); sectionIndex++) {
            var oldSection = before.sections().get(sectionIndex);
            var newSection = after.sections().get(sectionIndex);
            if (!oldSection.name().equals(newSection.name())) {
                sections.add(newSection);
            }
            for (int itemIndex = 0; itemIndex < oldSection.items().size(); itemIndex++) {
                var oldItem = oldSection.items().get(itemIndex);
                var newItem = newSection.items().get(itemIndex);
                if (!oldItem.name().equals(newItem.name())) {
                    items.add(new ItemInsert(newSection.id(), newItem));
                }
                for (int commentIndex = 0; commentIndex < oldItem.comments().size(); commentIndex++) {
                    var oldComment = oldItem.comments().get(commentIndex);
                    var newComment = newItem.comments().get(commentIndex);
                    if (!oldComment.name().equals(newComment.name())
                            || !oldComment.contentHtml().equals(newComment.contentHtml())) {
                        comments.add(new CommentInsert(newItem.id(), newComment));
                    }
                }
            }
        }
        jdbc.batchUpdate("UPDATE template_sections SET name = ? WHERE id = ? AND template_id = ?",
                sections, 200, (statement, section) -> {
                    statement.setString(1, section.name());
                    statement.setString(2, section.id());
                    statement.setString(3, before.id());
                });
        jdbc.batchUpdate("UPDATE template_items SET name = ? WHERE id = ? AND section_id = ?",
                items, 200, (statement, entry) -> {
                    statement.setString(1, entry.item().name());
                    statement.setString(2, entry.item().id());
                    statement.setString(3, entry.sectionId());
                });
        jdbc.batchUpdate("""
                        UPDATE template_comments SET name = ?, content_html = ?, preview_html = ?
                        WHERE id = ? AND item_id = ?
                        """,
                comments, 100, (statement, entry) -> {
                    statement.setString(1, entry.comment().name());
                    statement.setString(2, entry.comment().contentHtml());
                    statement.setString(3, entry.comment().previewHtml());
                    statement.setString(4, entry.comment().id());
                    statement.setString(5, entry.itemId());
                });
    }

    private TemplateSummary summary(ResultSet rs) throws SQLException {
        return new TemplateSummary(rs.getString("id"), rs.getString("name"), rs.getLong("version"),
                rs.getString("source_file_name"), rs.getString("source_sha256"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getString("duplicate_of"),
                new TemplateCounts(rs.getInt("section_count"), rs.getInt("item_count"), rs.getInt("comment_count")),
                rs.getInt("warning_count"));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode persisted template metadata");
        }
    }

    private ImportReport report(String value) {
        try {
            return json.readValue(value, ImportReport.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read persisted import report");
        }
    }

    private Map<String, String> metadata(String value) {
        try {
            return json.readValue(value, METADATA_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read persisted source metadata");
        }
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record TemplateRoot(TemplateSummary summary, ImportReport report) {}
    private record SectionRow(String id, String name, int position) {}
    private record ItemRow(String id, String name, int position, String sectionId) {}
    private record CommentRow(String itemId, TemplateComment comment) {}
    private record ItemInsert(String sectionId, TemplateItem item) {}
    private record CommentInsert(String itemId, TemplateComment comment) {}
}
