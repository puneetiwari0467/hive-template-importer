package com.hiveimporter.parser;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class PreviewRenderer {
    private static final Pattern HTML_TAG = Pattern.compile(
            "<(?:/?[a-zA-Z][a-zA-Z0-9:-]*(?:\\s[^<>]*)?\\s*/?>|!--|!DOCTYPE\\s)",
            Pattern.CASE_INSENSITIVE);
    private static final Safelist ALLOWED = new Safelist()
            .addTags("p", "br", "div", "span", "strong", "b", "em", "i", "u", "s", "strike",
                    "ul", "ol", "li", "blockquote", "pre", "code", "h1", "h2", "h3", "h4", "h5", "h6",
                    "table", "thead", "tbody", "tfoot", "tr", "th", "td", "hr", "a", "sub", "sup")
            .addAttributes("a", "href", "title")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("ol", "start")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
            .addEnforcedAttribute("a", "target", "_blank");

    public RenderedPreview render(String source) {
        var settings = new Document.OutputSettings()
                .prettyPrint(false).charset(StandardCharsets.UTF_8).escapeMode(Entities.EscapeMode.base);
        if (!HTML_TAG.matcher(source).find()) {
            String escaped = Entities.escape(source, settings)
                    .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>\n");
            return new RenderedPreview(escaped, false, false);
        }
        var dirty = Jsoup.parseBodyFragment(source);
        var cleaner = new Cleaner(ALLOWED);
        boolean restricted = !cleaner.isValid(dirty);
        var clean = cleaner.clean(dirty);
        clean.outputSettings(settings);
        return new RenderedPreview(clean.body().html(), true, restricted);
    }

    public record RenderedPreview(String html, boolean htmlSource, boolean restricted) {}
}
