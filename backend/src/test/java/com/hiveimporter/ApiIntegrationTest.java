package com.hiveimporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hiveimporter.api.ApiModels.TemplateDetail;
import com.hiveimporter.parser.SpectoraParser;
import com.hiveimporter.service.SampleWorkbook;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = "jdbc:mysql:.*",
        disabledReason = "Real MySQL integration requires TEST_DB_URL, TEST_DB_USERNAME and TEST_DB_PASSWORD.")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+",
        disabledReason = "Real MySQL integration requires a dedicated TEST_DB_USERNAME.")
@EnabledIfEnvironmentVariable(named = "TEST_DB_PASSWORD", matches = ".+",
        disabledReason = "Real MySQL integration requires TEST_DB_PASSWORD; there is no H2 fallback.")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.workspace-requests-per-hour=1000",
        "app.import-requests-per-hour=1000",
        "app.max-workspaces=1000",
        "app.max-templates-per-workspace=3",
        "app.max-comments-per-workspace=10000",
        "app.allowed-origins=http://localhost:5173",
        "logging.level.org.springframework=WARN",
        "logging.level.org.apache.catalina=WARN"
})
class ApiIntegrationTest {
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private SampleWorkbook sample;
    private final List<String> createdWorkspaces = new ArrayList<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):3307/[A-Za-z0-9_]+(?:\\?.*)?")
                || url.toLowerCase(java.util.Locale.ROOT).contains("atsradar")) {
            throw new IllegalStateException("Integration tests require an isolated local MySQL test database on port 3307, never ATSRadar or port 3306.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
    }

    @AfterEach
    void deleteOnlyThisTestsWorkspaces() {
        for (String id : createdWorkspaces) {
            jdbc.update("DELETE FROM workspaces WHERE id = ?", id);
        }
    }

    @Test
    void actualSampleIsPersistedWithAllNamesOriginalContentMetadataAndOrdering() throws Exception {
        var workspace = createWorkspace();
        JsonNode detail = get(workspace, workspace.templateId());
        TemplateDetail stored = json.treeToValue(detail, TemplateDetail.class);
        var parsed = sample.parsed();
        assertThat(stored.counts()).isEqualTo(parsed.counts());
        assertThat(stored.importReport()).isEqualTo(parsed.report());
        assertThat(stored.sourceSha256()).isEqualTo(parsed.sourceSha256());
        assertThat(stored.sourceFileName()).isEqualTo(SampleWorkbook.FILE_NAME);
        assertThat(stored.duplicateOf()).isNull();
        assertThat(stored.version()).isEqualTo(1);
        Set<String> ids = new HashSet<>();
        assertThat(ids.add(stored.id())).isTrue();
        for (int sectionIndex = 0; sectionIndex < parsed.sections().size(); sectionIndex++) {
            var expectedSection = parsed.sections().get(sectionIndex);
            var section = stored.sections().get(sectionIndex);
            assertThat(section.name()).isEqualTo(expectedSection.name());
            assertThat(section.position()).isEqualTo(expectedSection.position());
            assertThat(ids.add(section.id())).isTrue();
            assertThat(section.items()).hasSize(expectedSection.items().size());
            for (int itemIndex = 0; itemIndex < section.items().size(); itemIndex++) {
                var expectedItem = expectedSection.items().get(itemIndex);
                var item = section.items().get(itemIndex);
                assertThat(item.name()).isEqualTo(expectedItem.name());
                assertThat(item.position()).isEqualTo(expectedItem.position());
                assertThat(ids.add(item.id())).isTrue();
                assertThat(item.comments()).hasSize(expectedItem.comments().size());
                for (int commentIndex = 0; commentIndex < item.comments().size(); commentIndex++) {
                    var expected = expectedItem.comments().get(commentIndex);
                    var comment = item.comments().get(commentIndex);
                    assertThat(comment.name()).isEqualTo(expected.name());
                    assertThat(comment.contentHtml()).isEqualTo(expected.contentHtml());
                    assertThat(comment.originalHtml()).isEqualTo(expected.contentHtml());
                    assertThat(comment.previewHtml()).isEqualTo(expected.previewHtml());
                    assertThat(comment.type()).isEqualTo(expected.type());
                    assertThat(comment.position()).isEqualTo(expected.position());
                    assertThat(comment.sourceRow()).isEqualTo(expected.sourceRow());
                    assertThat(comment.sourceSheet()).isEqualTo(expected.sourceSheet());
                    assertThat(comment.metadata()).isEqualTo(expected.metadata());
                    assertThat(ids.add(comment.id())).isTrue();
                }
            }
        }
        assertThat(ids).hasSize(1 + 22 + 136 + 798);
        ids.forEach(UUID::fromString);
        assertThat(stored.createdAt()).isEqualTo(stored.updatedAt());
        assertThat(Base64.getUrlDecoder().decode(workspace.token()).length).isEqualTo(32);
        String savedHash = jdbc.queryForObject("SELECT token_hash FROM workspaces WHERE id = ?",
                String.class, workspace.id());
        assertThat(savedHash).isEqualTo(SpectoraParser.sha256(workspace.token().getBytes(StandardCharsets.UTF_8)));
        assertThat(savedHash.equals(workspace.token())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM templates WHERE workspace_id = ?",
                Integer.class, workspace.id())).isEqualTo(1);
        JsonNode listed = exchange("/api/templates", HttpMethod.GET, workspace.token(), null).getBody();
        assertThat(listed).isNotNull();
        assertThat(fields(listed)).containsExactly("templates");
        assertThat(listed.path("templates")).hasSize(1);
        assertSummary(listed.path("templates").get(0), detail);
        assertThat(get(workspace, workspace.templateId())).isEqualTo(detail);
    }

    @Test
    void healthQueriesRealDatabaseAndSampleDownloadIsByteExact() throws Exception {
        var health = http.getForEntity("/api/health", JsonNode.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).isEqualTo(json.readTree("{\"status\":\"UP\"}"));
        assertThat(health.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(health.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(health.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(health.getHeaders().getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'");
        var download = http.getForEntity("/api/sample", byte[].class);
        assertThat(download.getStatusCode().value()).isEqualTo(200);
        assertThat(download.getBody()).containsExactly(WorkbookFixtures.sample());
        assertThat(download.getHeaders().getContentDisposition().getFilename()).isEqualTo(WorkbookFixtures.SAMPLE);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).doesNotContainIgnoringCase("h2");
    }

    @Test
    void everyTemplateRouteRequiresTokenAndForeignTemplatesRemainNotFound() {
        var owner = createWorkspace();
        var other = createWorkspace();
        assertThat(other.token().equals(owner.token())).isFalse();
        var detail = get(owner, owner.templateId());
        for (String token : new String[] {null, "wrong", "A".repeat(43)}) {
            assertError(exchange("/api/templates", HttpMethod.GET, token, null), 401, "UNAUTHORIZED");
            assertError(exchange("/api/templates/" + owner.templateId(), HttpMethod.GET, token, null), 401, "UNAUTHORIZED");
        }
        assertError(exchange("/api/templates/" + owner.templateId(), HttpMethod.GET, other.token(), null), 404, "NOT_FOUND");
        assertError(exchange("/api/templates/" + owner.templateId(), HttpMethod.PUT, other.token(), update(detail)), 404, "NOT_FOUND");
        assertError(exchange("/api/templates/" + owner.templateId() + "/duplicate", HttpMethod.POST,
                other.token(), json.createObjectNode().put("name", "Not mine")), 404, "NOT_FOUND");
        JsonNode otherList = exchange("/api/templates", HttpMethod.GET, other.token(), null).getBody();
        assertThat(otherList.path("templates")).hasSize(1);
        assertThat(otherList.path("templates").get(0).path("id").asText()).isEqualTo(other.templateId());
        assertThat(get(owner, owner.templateId())).isEqualTo(detail);
    }

    @Test
    void editsPersistWithOptimisticVersionsAndImmutableOriginals() {
        var workspace = createWorkspace();
        JsonNode before = get(workspace, workspace.templateId());
        ObjectNode payload = update(before);
        payload.put("name", "Updated template 🚪");
        firstSection(payload).put("name", "Renamed section");
        firstItem(payload).put("name", "Renamed item");
        firstComment(payload).put("name", "Renamed comment");
        firstComment(payload).put("contentHtml", "<p>Edited &amp; safe <strong>text</strong></p><script>alert(1)</script>");
        var response = exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), payload);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode after = response.getBody();
        assertThat(after).isNotNull();
        assertThat(after.path("version").asLong()).isEqualTo(before.path("version").asLong() + 1);
        assertThat(after.path("name").asText()).isEqualTo("Updated template 🚪");
        assertThat(firstSection(after).path("name").asText()).isEqualTo("Renamed section");
        assertThat(firstItem(after).path("name").asText()).isEqualTo("Renamed item");
        assertThat(firstComment(after).path("name").asText()).isEqualTo("Renamed comment");
        assertThat(firstComment(after).path("contentHtml")).isEqualTo(firstComment(payload).path("contentHtml"));
        assertThat(firstComment(after).path("originalHtml")).isEqualTo(firstComment(before).path("originalHtml"));
        assertThat(firstComment(after).path("metadata")).isEqualTo(firstComment(before).path("metadata"));
        assertThat(firstComment(after).path("previewHtml").asText()).doesNotContain("<script", "alert(1)");
        assertThat(after.path("sourceSha256")).isEqualTo(before.path("sourceSha256"));
        assertThat(after.path("createdAt")).isEqualTo(before.path("createdAt"));
        assertThat(after.path("counts")).isEqualTo(before.path("counts"));
        assertThat(Instant.parse(after.path("updatedAt").asText()))
                .isAfterOrEqualTo(Instant.parse(before.path("updatedAt").asText()));
        assertThat(after.path("importReport").path("warnings").findValuesAsText("code")).contains("EDIT_PREVIEW_SANITIZED");
        assertThat(get(workspace, workspace.templateId())).isEqualTo(after);
        assertError(exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), payload),
                409, "VERSION_CONFLICT");
        assertThat(get(workspace, workspace.templateId())).isEqualTo(after);
        var noOp = exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), update(after));
        assertThat(noOp.getStatusCode().value()).isEqualTo(200);
        assertThat(noOp.getBody()).isEqualTo(after);
    }

    @Test
    void malformedHierarchiesRejectCompletelyWithoutPartialChanges() {
        var workspace = createWorkspace();
        var before = get(workspace, workspace.templateId());
        var wrongId = update(before);
        wrongId.put("name", "Must not persist");
        firstComment(wrongId).put("contentHtml", "Must not persist either");
        ObjectNode lastSection = (ObjectNode) wrongId.path("sections").get(wrongId.path("sections").size() - 1);
        ObjectNode lastItem = (ObjectNode) lastSection.path("items").get(lastSection.path("items").size() - 1);
        ((ObjectNode) lastItem.path("comments").get(lastItem.path("comments").size() - 1))
                .put("id", UUID.randomUUID().toString());
        assertInvalidUpdate(workspace, wrongId, before);

        var missing = update(before);
        ((ArrayNode) firstItem(missing).path("comments")).remove(0);
        assertInvalidUpdate(workspace, missing, before);

        var duplicated = update(before);
        ArrayNode comments = (ArrayNode) firstItem(duplicated).path("comments");
        comments.set(1, comments.get(0).deepCopy());
        assertInvalidUpdate(workspace, duplicated, before);

        var moved = update(before);
        ArrayNode first = (ArrayNode) firstItem(moved).path("comments");
        ArrayNode other = (ArrayNode) moved.path("sections").get(1).path("items").get(0).path("comments");
        JsonNode firstCopy = first.get(0).deepCopy();
        first.set(0, other.get(0).deepCopy());
        other.set(0, firstCopy);
        assertInvalidUpdate(workspace, moved, before);

        var extra = update(before);
        ((ArrayNode) firstItem(extra).path("comments")).add(firstComment(extra).deepCopy());
        assertInvalidUpdate(workspace, extra, before);
    }

    @Test
    void immutableUnknownAndInvalidJsonFieldsCannotBeWritten() {
        var workspace = createWorkspace();
        var before = get(workspace, workspace.templateId());
        var immutable = update(before);
        firstComment(immutable).put("originalHtml", "tampering");
        assertError(exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), immutable),
                400, "INVALID_REQUEST");
        var missingVersion = update(before);
        missingVersion.remove("version");
        assertError(exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), missingVersion),
                400, "INVALID_REQUEST");
        var invalidName = update(before);
        firstItem(invalidName).put("name", "");
        assertError(exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), invalidName),
                400, "INVALID_REQUEST");
        var wrongType = update(before);
        firstComment(wrongType).put("name", 123);
        assertError(exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), wrongType),
                400, "INVALID_REQUEST");
        assertThat(get(workspace, workspace.templateId())).isEqualTo(before);
    }

    @Test
    void validDifferentWorkbookImportsAndInvalidOrOversizedFilesDoNotCreatePartialRecords() throws Exception {
        var workspace = createWorkspace();
        byte[] alternate = WorkbookFixtures.alternate();
        var imported = upload(workspace, alternate, "another-export.xlsx", "Another template");
        assertThat(imported.getStatusCode().value()).isEqualTo(201);
        JsonNode template = imported.getBody();
        assertThat(template).isNotNull();
        assertThat(template.path("name").asText()).isEqualTo("Another template");
        assertThat(template.path("counts")).isEqualTo(json.readTree("{\"sections\":2,\"items\":2,\"comments\":3}"));
        assertThat(template.path("sourceSha256").asText()).isEqualTo(SpectoraParser.sha256(alternate));
        assertThat(get(workspace, template.path("id").asText())).isEqualTo(template);
        int templateCount = jdbc.queryForObject("SELECT COUNT(*) FROM templates WHERE workspace_id = ?", Integer.class, workspace.id());
        assertError(upload(workspace, "not an Excel workbook".getBytes(StandardCharsets.UTF_8), "not.xls", null), 422, "INVALID_EXPORT");
        try (var invalid = WorkbookFixtures.simple(false)) {
            WorkbookFixtures.row(invalid.createSheet("Unexpected sheet"), 0, "Extra contents");
            assertError(upload(workspace, WorkbookFixtures.bytes(invalid), "bad.xlsx", null), 422, "INVALID_EXPORT");
        }
        assertError(upload(workspace, new byte[SpectoraParser.MAX_UPLOAD_BYTES + 1], "oversize.xls", null),
                413, "UPLOAD_TOO_LARGE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM templates WHERE workspace_id = ?", Integer.class, workspace.id()))
                .isEqualTo(templateCount);
        assertThat(get(workspace, template.path("id").asText())).isEqualTo(template);
    }

    @Test
    void duplicateDeepCopiesEveryIdAndEditsRemainIndependentInBothDirections() {
        var workspace = createWorkspace();
        JsonNode seed = get(workspace, workspace.templateId());
        var initialEdit = update(seed);
        firstComment(initialEdit).put("contentHtml", "Edited before cloning");
        var original = exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), initialEdit).getBody();
        var cloneResponse = exchange("/api/templates/" + workspace.templateId() + "/duplicate", HttpMethod.POST,
                workspace.token(), json.createObjectNode().put("name", "Independent copy"));
        assertThat(cloneResponse.getStatusCode().value()).isEqualTo(201);
        JsonNode clone = cloneResponse.getBody();
        assertThat(clone).isNotNull();
        assertThat(clone.path("duplicateOf").asText()).isEqualTo(workspace.templateId());
        assertThat(clone.path("version").asLong()).isEqualTo(1);
        assertThat(clone.path("counts")).isEqualTo(original.path("counts"));
        assertThat(clone.path("importReport")).isEqualTo(original.path("importReport"));
        Set<String> originalIds = ids(original);
        Set<String> clonedIds = ids(clone);
        assertThat(originalIds).hasSize(957);
        assertThat(clonedIds).hasSize(957);
        assertThat(clonedIds).doesNotContainAnyElementsOf(originalIds);
        assertThat(withoutIds(clone.path("sections"))).isEqualTo(withoutIds(original.path("sections")));
        assertThat(firstComment(clone).path("originalHtml")).isEqualTo(firstComment(seed).path("originalHtml"));

        String cloneId = clone.path("id").asText();
        var cloneEdit = update(clone);
        cloneEdit.put("name", "Changed copy");
        firstComment(cloneEdit).put("contentHtml", "Only the copy changes");
        var changedClone = exchange("/api/templates/" + cloneId, HttpMethod.PUT, workspace.token(), cloneEdit);
        assertThat(changedClone.getStatusCode().value()).isEqualTo(200);
        assertThat(get(workspace, workspace.templateId())).isEqualTo(original);
        var originalEdit = update(original);
        firstComment(originalEdit).put("contentHtml", "Only the original changes");
        assertThat(exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), originalEdit)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(get(workspace, cloneId)).isEqualTo(changedClone.getBody());
    }

    @Test
    void deleteIsWorkspaceScopedCascadesChildrenAndKeepsIndependentCopies() {
        var owner = createWorkspace();
        var otherWorkspace = createWorkspace();
        JsonNode original = get(owner, owner.templateId());
        var cloneResponse = exchange("/api/templates/" + owner.templateId() + "/duplicate", HttpMethod.POST,
                owner.token(), json.createObjectNode().put("name", "Independent survivor"));
        assertThat(cloneResponse.getStatusCode().value()).isEqualTo(201);
        String cloneId = cloneResponse.getBody().path("id").asText();

        assertError(exchange("/api/templates/" + owner.templateId(), HttpMethod.DELETE,
                otherWorkspace.token(), null), 404, "NOT_FOUND");
        var deleted = exchange("/api/templates/" + owner.templateId(), HttpMethod.DELETE, owner.token(), null);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        assertThat(deleted.getBody()).isNull();
        assertError(exchange("/api/templates/" + owner.templateId(), HttpMethod.GET,
                owner.token(), null), 404, "NOT_FOUND");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM template_sections WHERE template_id = ?",
                Integer.class, owner.templateId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM template_items i
                JOIN template_sections s ON s.id = i.section_id
                WHERE s.template_id = ?
                """, Integer.class, owner.templateId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM template_comments c
                JOIN template_items i ON i.id = c.item_id
                JOIN template_sections s ON s.id = i.section_id
                WHERE s.template_id = ?
                """, Integer.class, owner.templateId())).isZero();

        JsonNode survivingCopy = get(owner, cloneId);
        assertThat(survivingCopy.path("name").asText()).isEqualTo("Independent survivor");
        assertThat(survivingCopy.path("duplicateOf").isNull()).isTrue();
        assertThat(survivingCopy.path("counts")).isEqualTo(original.path("counts"));

        var deleteCopy = exchange("/api/templates/" + cloneId, HttpMethod.DELETE, owner.token(), null);
        assertThat(deleteCopy.getStatusCode().value()).isEqualTo(204);
        JsonNode listed = exchange("/api/templates", HttpMethod.GET, owner.token(), null).getBody();
        assertThat(listed.path("templates")).isEmpty();
    }

    @Test
    void concurrentSavesNeverLoseEditsAndExactlyOneVersionWins() throws Exception {
        var workspace = createWorkspace();
        var before = get(workspace, workspace.templateId());
        var first = update(before).put("name", "Concurrent first");
        var second = update(before).put("name", "Concurrent second");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = CompletableFuture.supplyAsync(() -> saveAfter(start, workspace, first), executor);
            var two = CompletableFuture.supplyAsync(() -> saveAfter(start, workspace, second), executor);
            start.countDown();
            var a = one.get(45, TimeUnit.SECONDS);
            var b = two.get(45, TimeUnit.SECONDS);
            assertThat(List.of(a.getStatusCode().value(), b.getStatusCode().value())).containsExactlyInAnyOrder(200, 409);
            JsonNode winner = a.getStatusCode().value() == 200 ? a.getBody() : b.getBody();
            assertThat(get(workspace, workspace.templateId())).isEqualTo(winner);
            assertThat(winner.path("version").asLong()).isEqualTo(2);
        }
    }

    @Test
    void transactionalTemplateQuotaRejectsExtraCopiesButStillReturns404ForForeignIds() {
        var workspace = createWorkspace();
        for (int index = 0; index < 2; index++) {
            var copy = exchange("/api/templates/" + workspace.templateId() + "/duplicate", HttpMethod.POST,
                    workspace.token(), json.createObjectNode().put("name", "Copy " + index));
            assertThat(copy.getStatusCode().value()).isEqualTo(201);
        }
        assertError(exchange("/api/templates/" + workspace.templateId() + "/duplicate", HttpMethod.POST,
                workspace.token(), json.createObjectNode().put("name", "Over capacity")), 429, "DEMO_LIMIT");
        assertError(exchange("/api/templates/" + UUID.randomUUID() + "/duplicate", HttpMethod.POST,
                workspace.token(), json.createObjectNode().put("name", "Unknown")), 404, "NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM templates WHERE workspace_id = ?", Integer.class, workspace.id()))
                .isEqualTo(3);
    }

    @Test
    void corsPreflightAndUnauthorizedResponsesAreReadableOnlyByConfiguredOrigins() {
        var headers = new HttpHeaders();
        headers.setOrigin("http://localhost:5173");
        headers.setAccessControlRequestMethod(HttpMethod.PUT);
        headers.setAccessControlRequestHeaders(List.of("Authorization", "Content-Type"));
        var preflight = http.exchange("/api/templates/" + UUID.randomUUID(), HttpMethod.OPTIONS,
                new HttpEntity<>(headers), String.class);
        assertThat(preflight.getStatusCode().value()).isEqualTo(200);
        assertThat(preflight.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
        assertThat(preflight.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.PUT);
        var originOnly = new HttpHeaders();
        originOnly.setOrigin("http://localhost:5173");
        var unauthorized = http.exchange("/api/templates", HttpMethod.GET, new HttpEntity<>(originOnly), JsonNode.class);
        assertError(unauthorized, 401, "UNAUTHORIZED");
        assertThat(unauthorized.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
        originOnly.setOrigin("https://untrusted.example");
        var denied = http.exchange("/api/templates", HttpMethod.GET, new HttpEntity<>(originOnly), JsonNode.class);
        assertError(denied, 403, "ORIGIN_NOT_ALLOWED");
        assertThat(denied.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void bundledFrontendAndAssetsArePublicWhileTemplateApiStaysPrivate() {
        var headers = new HttpHeaders();
        headers.setOrigin("https://azure-deployment.example");
        headers.set("X-Forwarded-Proto", "https");
        var home = http.exchange("/", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(home.getStatusCode().value()).isEqualTo(200);
        assertThat(home.getHeaders().getContentType()).isNotNull().matches(type -> type.isCompatibleWith(MediaType.TEXT_HTML));
        assertThat(home.getHeaders().getFirst("Content-Security-Policy"))
                .contains("default-src 'self'", "script-src 'self';", "style-src 'self'");
        assertThat(home.getBody()).isNotBlank();
        var document = Jsoup.parse(home.getBody());
        assertThat(document.select("#root")).hasSize(1);
        assertThat(document.select("script[src]")).isNotEmpty();
        assertThat(document.select("link[rel=stylesheet][href]")).isNotEmpty();
        for (var script : document.select("script[src]")) {
            String path = script.attr("src");
            assertThat(path).startsWith("/assets/");
            var asset = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            assertThat(asset.getStatusCode().value()).isEqualTo(200);
            assertThat(asset.getHeaders().getContentType()).isNotNull();
            assertThat(asset.getHeaders().getContentType().toString()).contains("javascript");
            assertThat(asset.getBody()).isNotNull();
            assertThat(asset.getBody().length).isGreaterThan(100);
        }
        for (var stylesheet : document.select("link[rel=stylesheet][href]")) {
            var asset = http.exchange(stylesheet.attr("href"), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            assertThat(asset.getStatusCode().value()).isEqualTo(200);
            assertThat(asset.getHeaders().getContentType()).isNotNull().matches(type ->
                    type.isCompatibleWith(MediaType.parseMediaType("text/css")));
        }
        assertError(exchange("/api/templates", HttpMethod.GET, null, null), 401, "UNAUTHORIZED");
    }

    private Workspace createWorkspace() {
        var response = http.postForEntity("/api/workspaces", null, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(fields(body)).containsExactlyInAnyOrder("token", "workspaceId", "templateId");
        String workspaceId = body.path("workspaceId").asText();
        createdWorkspaces.add(workspaceId);
        return new Workspace(workspaceId, body.path("token").asText(), body.path("templateId").asText());
    }

    private JsonNode get(Workspace workspace, String templateId) {
        var response = exchange("/api/templates/" + templateId, HttpMethod.GET, workspace.token(), null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(fields(body)).containsExactlyInAnyOrder("id", "name", "version", "sourceFileName", "sourceSha256",
                "createdAt", "updatedAt", "duplicateOf", "counts", "warningCount", "importReport", "sections");
        return body;
    }

    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, String token, JsonNode payload) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(payload, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> upload(Workspace workspace, byte[] bytes, String fileName, String name) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(workspace.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        if (name != null) {
            parts.add("name", name);
        }
        return http.exchange("/api/templates/import", HttpMethod.POST, new HttpEntity<>(parts, headers), JsonNode.class);
    }

    private ObjectNode update(JsonNode detail) {
        var payload = json.createObjectNode();
        payload.set("version", detail.get("version"));
        payload.set("name", detail.get("name"));
        var sections = payload.putArray("sections");
        for (JsonNode section : detail.path("sections")) {
            var nextSection = sections.addObject().put("id", section.path("id").asText()).put("name", section.path("name").asText());
            var items = nextSection.putArray("items");
            for (JsonNode item : section.path("items")) {
                var nextItem = items.addObject().put("id", item.path("id").asText()).put("name", item.path("name").asText());
                var comments = nextItem.putArray("comments");
                for (JsonNode comment : item.path("comments")) {
                    comments.addObject().put("id", comment.path("id").asText()).put("name", comment.path("name").asText())
                            .put("contentHtml", comment.path("contentHtml").asText());
                }
            }
        }
        return payload;
    }

    private void assertInvalidUpdate(Workspace workspace, ObjectNode update, JsonNode before) {
        assertError(exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), update), 400, "INVALID_UPDATE");
        assertThat(get(workspace, workspace.templateId())).isEqualTo(before);
    }

    private static void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
        assertThat(response.getBody().path("message").asText()).isNotBlank();
        assertThat(response.getBody().path("details").isArray()).isTrue();
        assertThat(fields(response.getBody())).containsExactlyInAnyOrder("code", "message", "details");
    }

    private static void assertSummary(JsonNode summary, JsonNode detail) {
        assertThat(fields(summary)).containsExactlyInAnyOrder("id", "name", "version", "sourceFileName", "sourceSha256",
                "createdAt", "updatedAt", "duplicateOf", "counts", "warningCount");
        summary.fieldNames().forEachRemaining(field -> assertThat(summary.path(field)).isEqualTo(detail.path(field)));
    }

    private static List<String> fields(JsonNode node) {
        var result = new ArrayList<String>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static ObjectNode firstSection(JsonNode tree) {
        return (ObjectNode) tree.path("sections").get(0);
    }

    private static ObjectNode firstItem(JsonNode tree) {
        return (ObjectNode) firstSection(tree).path("items").get(0);
    }

    private static ObjectNode firstComment(JsonNode tree) {
        return (ObjectNode) firstItem(tree).path("comments").get(0);
    }

    private static Set<String> ids(JsonNode tree) {
        return new HashSet<>(tree.findValuesAsText("id"));
    }

    private static JsonNode withoutIds(JsonNode source) {
        JsonNode copy = source.deepCopy();
        removeIds(copy);
        return copy;
    }

    private static void removeIds(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove("id");
        }
        Iterator<JsonNode> children = node.elements();
        children.forEachRemaining(ApiIntegrationTest::removeIds);
    }

    private ResponseEntity<JsonNode> saveAfter(CountDownLatch start, Workspace workspace, ObjectNode payload) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test start timed out");
            }
            return exchange("/api/templates/" + workspace.templateId(), HttpMethod.PUT, workspace.token(), payload);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test interrupted");
        }
    }

    private record Workspace(String id, String token, String templateId) {}
}
