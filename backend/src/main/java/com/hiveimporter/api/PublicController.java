package com.hiveimporter.api;

import com.hiveimporter.api.ApiModels.WorkspaceCreated;
import com.hiveimporter.service.DemoRateGuard;
import com.hiveimporter.service.SampleWorkbook;
import com.hiveimporter.service.TemplateService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PublicController {
    private final TemplateService service;
    private final SampleWorkbook sample;
    private final JdbcTemplate jdbc;
    private final DemoRateGuard guard;

    public PublicController(TemplateService service, SampleWorkbook sample, JdbcTemplate jdbc, DemoRateGuard guard) {
        this.service = service;
        this.sample = sample;
        this.jdbc = jdbc;
        this.guard = guard;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        try {
            Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
            if (value == null || value != 1) {
                throw new IllegalStateException("Database health query returned an invalid result");
            }
            return Map.of("status", "UP");
        } catch (DataAccessException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                    "The database is currently unavailable. Please try again shortly.");
        }
    }

    @GetMapping("/sample")
    public ResponseEntity<byte[]> sample() {
        byte[] bytes = sample.bytes();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(SampleWorkbook.FILE_NAME, StandardCharsets.UTF_8).build().toString())
                .body(bytes);
    }

    @PostMapping("/workspaces")
    public ResponseEntity<WorkspaceCreated> createWorkspace(HttpServletRequest request) {
        guard.workspaceCreation(request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createWorkspace());
    }
}
