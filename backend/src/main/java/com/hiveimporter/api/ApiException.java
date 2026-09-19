package com.hiveimporter.api;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    public ApiException(HttpStatus status, String code, String message, String... details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.of(details);
    }

    public HttpStatus status() {
        return status;
    }

    public ApiModels.ApiErrorBody body() {
        return new ApiModels.ApiErrorBody(code, getMessage(), details);
    }

    public static ApiException invalidExport(String message, String... details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EXPORT", message, details);
    }

    public static ApiException invalidUpdate(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_UPDATE", message);
    }

    public static ApiException tooLarge(String message) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", message);
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "A valid workspace bearer token is required. Create a new workspace if the token was lost.");
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Template not found in this workspace.");
    }

    public static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "This template changed in another tab. Reload it before saving again.");
    }

    public static ApiException limit(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DEMO_LIMIT", message);
    }
}
