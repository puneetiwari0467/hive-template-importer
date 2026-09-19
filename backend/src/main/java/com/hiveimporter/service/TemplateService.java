package com.hiveimporter.service;

import com.hiveimporter.api.ApiException;
import com.hiveimporter.api.ApiModels.CommentUpdate;
import com.hiveimporter.api.ApiModels.ImportReport;
import com.hiveimporter.api.ApiModels.ImportWarning;
import com.hiveimporter.api.ApiModels.ItemUpdate;
import com.hiveimporter.api.ApiModels.SectionUpdate;
import com.hiveimporter.api.ApiModels.TemplateComment;
import com.hiveimporter.api.ApiModels.TemplateDetail;
import com.hiveimporter.api.ApiModels.TemplateItem;
import com.hiveimporter.api.ApiModels.TemplateSection;
import com.hiveimporter.api.ApiModels.TemplateSummary;
import com.hiveimporter.api.ApiModels.TemplateUpdate;
import com.hiveimporter.api.ApiModels.WorkspaceCreated;
import com.hiveimporter.config.AppProperties;
import com.hiveimporter.parser.ParsedWorkbook;
import com.hiveimporter.parser.PreviewRenderer;
import com.hiveimporter.parser.SpectoraParser;
import com.hiveimporter.persistence.TemplateRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateService {
    private final TemplateRepository repository;
    private final SampleWorkbook sample;
    private final PreviewRenderer previews;
    private final AppProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TemplateService(TemplateRepository repository, SampleWorkbook sample, PreviewRenderer previews,
            AppProperties properties, Clock clock) {
        this.repository = repository;
        this.sample = sample;
        this.previews = previews;
        this.properties = properties;
        this.clock = clock;
    }

    public String authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw ApiException.unauthorized();
        }
        return repository.workspaceForTokenHash(SpectoraParser.sha256(token.getBytes(StandardCharsets.UTF_8)))
                .orElseThrow(ApiException::unauthorized);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkspaceCreated createWorkspace() {
        repository.lockWorkspaceQuota(properties.maxWorkspaces());
        if (sample.parsed().counts().comments() > properties.maxCommentsPerWorkspace()) {
            throw ApiException.limit("Workspace comment capacity is too small for the bundled demo sample.");
        }
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String workspaceId = id();
        Instant now = now();
        repository.createWorkspace(workspaceId, SpectoraParser.sha256(token.getBytes(StandardCharsets.UTF_8)), now);
        TemplateDetail seed = imported(sample.parsed(), defaultName(sample.parsed().sourceFileName()), now);
        repository.insert(workspaceId, seed);
        return new WorkspaceCreated(token, workspaceId, seed.id());
    }

    @Transactional(readOnly = true)
    public List<TemplateSummary> list(String workspaceId) {
        return repository.list(workspaceId);
    }

    @Transactional(readOnly = true)
    public TemplateDetail get(String workspaceId, String templateId) {
        return repository.find(workspaceId, templateId, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TemplateDetail importWorkbook(String workspaceId, ParsedWorkbook workbook, String requestedName) {
        String name = requestedName == null ? defaultName(workbook.sourceFileName()) : validName(requestedName);
        repository.checkTemplateCapacity(workspaceId, workbook.counts().comments(),
                properties.maxTemplatesPerWorkspace(), properties.maxCommentsPerWorkspace());
        var template = imported(workbook, name, now());
        repository.insert(workspaceId, template);
        return template;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TemplateDetail duplicate(String workspaceId, String templateId, String name) {
        validName(name);
        repository.requireOwned(workspaceId, templateId);
        // Workspace locks precede template locks on every operation that creates additional templates.
        repository.checkTemplateCapacity(workspaceId, 0,
                properties.maxTemplatesPerWorkspace(), properties.maxCommentsPerWorkspace());
        var original = repository.find(workspaceId, templateId, true);
        repository.checkTemplateCapacity(workspaceId, original.counts().comments(),
                properties.maxTemplatesPerWorkspace(), properties.maxCommentsPerWorkspace());
        var sections = original.sections().stream().map(section ->
                new TemplateSection(id(), section.name(), section.position(),
                        section.items().stream().map(item ->
                                new TemplateItem(id(), item.name(), item.position(),
                                        item.comments().stream().map(comment -> new TemplateComment(
                                                id(), comment.name(), comment.position(), comment.type(), comment.contentHtml(),
                                                comment.previewHtml(), comment.originalHtml(), comment.sourceRow(),
                                                comment.sourceSheet(), comment.metadata())).toList())).toList())).toList();
        Instant now = now();
        var copy = new TemplateDetail(id(), name, 1, original.sourceFileName(), original.sourceSha256(),
                now, now, original.id(), original.counts(), original.warningCount(), original.importReport(), sections);
        repository.insert(workspaceId, copy);
        return copy;
    }

    @Transactional
    public TemplateDetail update(String workspaceId, String templateId, TemplateUpdate update) {
        var original = repository.find(workspaceId, templateId, true);
        if (update.version() == null || update.version() != original.version()) {
            throw ApiException.conflict();
        }
        validName(update.name());
        var sectionInputs = index(update.sections(), original.sections().size(), SectionUpdate::id, "Section");
        var sections = new ArrayList<TemplateSection>();
        for (var section : original.sections()) {
            var sectionInput = require(sectionInputs, section.id(), "Section");
            var itemInputs = index(sectionInput.items(), section.items().size(), ItemUpdate::id, "Item");
            var items = new ArrayList<TemplateItem>();
            for (var item : section.items()) {
                var itemInput = require(itemInputs, item.id(), "Item");
                var commentInputs = index(itemInput.comments(), item.comments().size(), CommentUpdate::id, "Comment");
                var comments = new ArrayList<TemplateComment>();
                for (var comment : item.comments()) {
                    var input = require(commentInputs, comment.id(), "Comment");
                    String preview = comment.contentHtml().equals(input.contentHtml()) ? comment.previewHtml()
                            : previews.render(input.contentHtml()).html();
                    comments.add(new TemplateComment(comment.id(), input.name(), comment.position(), comment.type(),
                            input.contentHtml(), preview, comment.originalHtml(), comment.sourceRow(),
                            comment.sourceSheet(), comment.metadata()));
                }
                items.add(new TemplateItem(item.id(), itemInput.name(), item.position(), comments));
            }
            sections.add(new TemplateSection(section.id(), sectionInput.name(), section.position(), items));
        }
        if (original.name().equals(update.name()) && original.sections().equals(sections)) {
            return original;
        }
        var report = reportWithEditWarnings(original.importReport(), sections);
        var changed = new TemplateDetail(original.id(), update.name(), original.version() + 1,
                original.sourceFileName(), original.sourceSha256(), original.createdAt(), now(), original.duplicateOf(),
                original.counts(), report.warnings().size(), report, sections);
        repository.update(workspaceId, original, changed);
        return changed;
    }

    private ImportReport reportWithEditWarnings(ImportReport original, List<TemplateSection> sections) {
        var warnings = new ArrayList<>(original.warnings().stream()
                .filter(warning -> !"EDIT_PREVIEW_SANITIZED".equals(warning.code())).toList());
        for (var section : sections) {
            for (var item : section.items()) {
                for (var comment : item.comments()) {
                    if (warnings.size() < 500 && !comment.contentHtml().equals(comment.originalHtml())
                            && previews.render(comment.contentHtml()).restricted()) {
                        warnings.add(new ImportWarning("EDIT_PREVIEW_SANITIZED", "warning",
                                "The edited source contains HTML that is intentionally excluded from the safe preview. "
                                        + "The edited text and immutable imported original are preserved.",
                                comment.sourceSheet(), comment.sourceRow(), "Comment Text", null));
                    }
                }
            }
        }
        return new ImportReport(original.sourceRows(), original.importedRows(), original.sourceColumns(),
                warnings, original.notes());
    }

    private TemplateDetail imported(ParsedWorkbook workbook, String name, Instant now) {
        var sections = workbook.sections().stream().map(section -> new TemplateSection(
                id(), section.name(), section.position(), section.items().stream().map(item -> new TemplateItem(
                        id(), item.name(), item.position(), item.comments().stream().map(comment -> new TemplateComment(
                                id(), comment.name(), comment.position(), comment.type(), comment.contentHtml(),
                                comment.previewHtml(), comment.contentHtml(), comment.sourceRow(), comment.sourceSheet(),
                                comment.metadata())).toList())).toList())).toList();
        return new TemplateDetail(id(), name, 1, workbook.sourceFileName(), workbook.sourceSha256(), now, now, null,
                workbook.counts(), workbook.report().warnings().size(), workbook.report(), sections);
    }

    private static <T> Map<String, T> index(List<T> values, int expectedSize, Function<T, String> key, String kind) {
        if (values == null || values.size() != expectedSize) {
            throw ApiException.invalidUpdate(kind + " counts must exactly match the existing hierarchy; adding or removing records is unsupported.");
        }
        var result = new LinkedHashMap<String, T>();
        for (T value : values) {
            if (value == null || key.apply(value) == null || result.putIfAbsent(key.apply(value), value) != null) {
                throw ApiException.invalidUpdate(kind + " IDs must be present and unique.");
            }
        }
        return result;
    }

    private static <T> T require(Map<String, T> values, String id, String kind) {
        T value = values.get(id);
        if (value == null) {
            throw ApiException.invalidUpdate(kind + " IDs must belong to this template and retain their original parent.");
        }
        return value;
    }

    private static String defaultName(String fileName) {
        int extension = fileName.lastIndexOf('.');
        String name = extension > 0 ? fileName.substring(0, extension) : fileName;
        return name.isBlank() ? "Imported template" : name;
    }

    private static String validName(String name) {
        if (name == null || name.isBlank() || name.length() > 255) {
            throw ApiException.invalidUpdate("The template name must contain 1–255 characters.");
        }
        return name;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
