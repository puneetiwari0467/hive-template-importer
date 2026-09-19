package com.hiveimporter.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {}

    public record TemplateCounts(int sections, int items, int comments) {}

    public record ImportWarning(
            String code, String severity, String message, String sheet, Integer row, String column, String value) {}

    public record ImportReport(
            int sourceRows, int importedRows, List<String> sourceColumns,
            List<ImportWarning> warnings, List<String> notes) {
        public ImportReport {
            sourceColumns = List.copyOf(sourceColumns);
            warnings = List.copyOf(warnings);
            notes = List.copyOf(notes);
        }
    }

    public record TemplateComment(
            String id, String name, int position, String type, String contentHtml,
            String previewHtml, String originalHtml, int sourceRow, String sourceSheet,
            Map<String, String> metadata) {
        public TemplateComment {
            metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }

    public record TemplateItem(String id, String name, int position, List<TemplateComment> comments) {
        public TemplateItem {
            comments = List.copyOf(comments);
        }
    }

    public record TemplateSection(String id, String name, int position, List<TemplateItem> items) {
        public TemplateSection {
            items = List.copyOf(items);
        }
    }

    public record TemplateSummary(
            String id, String name, long version, String sourceFileName, String sourceSha256,
            Instant createdAt, Instant updatedAt, String duplicateOf, TemplateCounts counts, int warningCount) {}

    public record TemplateDetail(
            String id, String name, long version, String sourceFileName, String sourceSha256,
            Instant createdAt, Instant updatedAt, String duplicateOf, TemplateCounts counts,
            int warningCount, ImportReport importReport, List<TemplateSection> sections) {
        public TemplateDetail {
            sections = List.copyOf(sections);
        }

        public TemplateSummary summary() {
            return new TemplateSummary(id, name, version, sourceFileName, sourceSha256,
                    createdAt, updatedAt, duplicateOf, counts, warningCount);
        }
    }

    public record WorkspaceCreated(String token, String workspaceId, String templateId) {}

    public record TemplateList(List<TemplateSummary> templates) {}

    public record TemplateUpdate(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 255) String name,
            @NotNull @Size(min = 1, max = 5000) List<@NotNull @Valid SectionUpdate> sections) {}

    public record SectionUpdate(
            @NotBlank @Size(max = 36) String id,
            @NotBlank @Size(max = 512) String name,
            @NotNull @Size(min = 1, max = 5000) List<@NotNull @Valid ItemUpdate> items) {}

    public record ItemUpdate(
            @NotBlank @Size(max = 36) String id,
            @NotBlank @Size(max = 512) String name,
            @NotNull @Size(min = 1, max = 5000) List<@NotNull @Valid CommentUpdate> comments) {}

    public record CommentUpdate(
            @NotBlank @Size(max = 36) String id,
            @NotBlank @Size(max = 512) String name,
            @NotNull @Size(max = 65535) String contentHtml) {}

    public record DuplicateRequest(@NotBlank @Size(max = 255) String name) {}

    public record ApiErrorBody(String code, String message, List<String> details) {
        public ApiErrorBody {
            details = List.copyOf(details);
        }
    }
}
