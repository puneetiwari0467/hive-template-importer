package com.hiveimporter.parser;

import com.hiveimporter.api.ApiModels.ImportReport;
import com.hiveimporter.api.ApiModels.TemplateCounts;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ParsedWorkbook(
        String sourceFileName, String sourceSha256, ImportReport report, List<ParsedSection> sections) {
    public ParsedWorkbook {
        sections = List.copyOf(sections);
    }

    public TemplateCounts counts() {
        return new TemplateCounts(sections.size(),
                sections.stream().mapToInt(section -> section.items().size()).sum(),
                sections.stream().flatMap(section -> section.items().stream())
                        .mapToInt(item -> item.comments().size()).sum());
    }

    public record ParsedSection(String name, int position, List<ParsedItem> items) {
        public ParsedSection {
            items = List.copyOf(items);
        }
    }

    public record ParsedItem(String name, int position, List<ParsedComment> comments) {
        public ParsedItem {
            comments = List.copyOf(comments);
        }
    }

    public record ParsedComment(
            String name, int position, String type, String contentHtml, String previewHtml,
            int sourceRow, String sourceSheet, Map<String, String> metadata) {
        public ParsedComment {
            metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }
}
