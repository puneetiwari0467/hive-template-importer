package com.hiveimporter.api;

import com.hiveimporter.api.ApiModels.DuplicateRequest;
import com.hiveimporter.api.ApiModels.TemplateDetail;
import com.hiveimporter.api.ApiModels.TemplateList;
import com.hiveimporter.api.ApiModels.TemplateUpdate;
import com.hiveimporter.config.WorkspaceAuthenticationFilter;
import com.hiveimporter.parser.SpectoraParser;
import com.hiveimporter.service.DemoRateGuard;
import com.hiveimporter.service.TemplateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {
    private final TemplateService service;
    private final SpectoraParser parser;
    private final DemoRateGuard guard;

    public TemplateController(TemplateService service, SpectoraParser parser, DemoRateGuard guard) {
        this.service = service;
        this.parser = parser;
        this.guard = guard;
    }

    @GetMapping
    public TemplateList list(HttpServletRequest request) {
        return new TemplateList(service.list(workspace(request)));
    }

    @GetMapping("/{id}")
    public TemplateDetail get(@PathVariable UUID id, HttpServletRequest request) {
        return service.get(workspace(request), id.toString());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TemplateDetail> importWorkbook(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            HttpServletRequest request) {
        String workspaceId = workspace(request);
        guard.importAttempt(workspaceId);
        if (request instanceof MultipartHttpServletRequest multipart
                && (multipart.getMultiFileMap().size() != 1 || multipart.getFiles("file").size() != 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Send exactly one spreadsheet in the file part.");
        }
        if (file.getSize() > SpectoraParser.MAX_UPLOAD_BYTES) {
            throw ApiException.tooLarge("Spreadsheet exceeds the 10 MiB upload limit.");
        }
        try (var input = file.getInputStream()) {
            var parsed = parser.parse(input, file.getOriginalFilename());
            return ResponseEntity.status(HttpStatus.CREATED).body(service.importWorkbook(workspaceId, parsed, name));
        } catch (IOException exception) {
            throw ApiException.invalidExport("The uploaded spreadsheet could not be read. Please select it again.");
        }
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TemplateDetail update(@PathVariable UUID id, @Valid @RequestBody TemplateUpdate update,
            HttpServletRequest request) {
        return service.update(workspace(request), id.toString(), update);
    }

    @PostMapping(value = "/{id}/duplicate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TemplateDetail> duplicate(@PathVariable UUID id, @Valid @RequestBody DuplicateRequest duplicate,
            HttpServletRequest request) {
        String workspaceId = workspace(request);
        guard.importAttempt(workspaceId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.duplicate(workspaceId, id.toString(), duplicate.name()));
    }

    private static String workspace(HttpServletRequest request) {
        Object id = request.getAttribute(WorkspaceAuthenticationFilter.WORKSPACE_ATTRIBUTE);
        if (!(id instanceof String workspaceId)) {
            throw ApiException.unauthorized();
        }
        return workspaceId;
    }
}
