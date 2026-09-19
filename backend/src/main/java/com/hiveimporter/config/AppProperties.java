package com.hiveimporter.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotEmpty List<String> allowedOrigins,
        @Min(1) @Max(10000) int maxWorkspaces,
        @Min(1) @Max(100) int maxTemplatesPerWorkspace,
        @Min(1) @Max(500000) int maxCommentsPerWorkspace,
        @Min(1) @Max(10000) int workspaceRequestsPerHour,
        @Min(1) @Max(10000) int importRequestsPerHour) {}
