package com.hiveimporter.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class PreviewRendererTest {
    private final PreviewRenderer renderer = new PreviewRenderer();

    @Test
    void plainTextIsEscapedExactlyAndNewlinesAreVisible() {
        var preview = renderer.render("A < 5 & B > 2\r\n\"quoted\"\rfinal\n&lt;literal&gt;");
        assertThat(preview.htmlSource()).isFalse();
        assertThat(preview.restricted()).isFalse();
        assertThat(preview.html()).isEqualTo("A &lt; 5 &amp; B &gt; 2<br>\n&quot;quoted&quot;<br>\nfinal<br>\n&amp;lt;literal&amp;gt;");
    }

    @Test
    void safeRichHtmlKeepsListsTablesEmphasisAndUnicode() {
        String source = "<p>Hello <strong>世界 🚪</strong></p><ul><li>One</li><li>Two</li></ul>"
                + "<table><tbody><tr><td colspan=\"2\">Cell</td></tr></tbody></table>";
        var preview = renderer.render(source);
        assertThat(preview.htmlSource()).isTrue();
        assertThat(preview.restricted()).isFalse();
        var dom = Jsoup.parseBodyFragment(preview.html());
        assertThat(dom.select("strong").text()).isEqualTo("世界 🚪");
        assertThat(dom.select("li")).hasSize(2);
        assertThat(dom.select("td").attr("colspan")).isEqualTo("2");
    }

    @Test
    void scriptsStylesEventsImagesEmbedsSvgFormsAndActiveUrlsAreRemoved() {
        String source = """
                <script>window.stolen = 1</script><style>@import 'https://example.invalid/a.css';</style>
                <p onclick="alert(1)" style="background:url(https://example.invalid/image)">Text</p>
                <img src="https://example.invalid/tracker" onerror="alert(1)">
                <iframe src="https://example.invalid"></iframe><object data="bad"></object>
                <svg onload="alert(1)"><circle /></svg><form action="/steal"><input name="secret"></form>
                <a href="javascript:alert(1)">Bad</a><a href="data:text/html,bad">Data</a>
                <a href="//example.invalid/relative">Relative</a>
                """;
        var preview = renderer.render(source);
        assertThat(preview.restricted()).isTrue();
        var dom = Jsoup.parseBodyFragment(preview.html());
        assertThat(dom.select("script,style,img,iframe,object,svg,form,input,[onclick],[onload],[onerror],[style]")).isEmpty();
        assertThat(dom.select("a[href]")).isEmpty();
        assertThat(preview.html()).doesNotContain("window.stolen", "https://example.invalid/tracker", "@import");
        assertThat(source).contains("<script>", "<img");
    }

    @Test
    void absoluteLinksAreHardenedWithoutFetchingAnything() {
        var rendered = renderer.render("<a href=\"https://example.invalid/path?q=1&amp;x=2\" title=\"Read\">Link</a>");
        var anchor = Jsoup.parseBodyFragment(rendered.html()).selectFirst("a");
        assertThat(anchor).isNotNull();
        assertThat(anchor.attr("href")).isEqualTo("https://example.invalid/path?q=1&x=2");
        assertThat(anchor.attr("target")).isEqualTo("_blank");
        assertThat(anchor.attr("rel")).isEqualTo("noopener noreferrer nofollow");
    }

    @Test
    void encodedMixedCaseJavascriptAndMalformedMarkupCannotBecomeActive() {
        var rendered = renderer.render("<A HREF=\"jav&#x61;script:alert(1)\">x</A>"
                + "<img/src=x onerror=alert(1)><scrIpt\n>alert(1)</sCript>");
        var dom = Jsoup.parseBodyFragment(rendered.html());
        assertThat(dom.select("img,script,[onerror],a[href]")).isEmpty();
        assertThat(rendered.restricted()).isTrue();
    }

    @Test
    void emptyAndWhitespaceSourceRemainRepresentable() {
        assertThat(renderer.render("").html()).isEmpty();
        assertThat(renderer.render(" \n ").html()).isEqualTo(" <br>\n ");
    }
}
