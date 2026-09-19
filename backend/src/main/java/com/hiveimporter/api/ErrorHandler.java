package com.hiveimporter.api;

import com.hiveimporter.api.ApiModels.ApiErrorBody;
import jakarta.validation.ConstraintViolationException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorBody> api(ApiException exception) {
        var response = ResponseEntity.status(exception.status());
        if (exception.status() == HttpStatus.TOO_MANY_REQUESTS) {
            response.header("Retry-After", "3600");
        }
        if (exception.status() == HttpStatus.UNAUTHORIZED) {
            response.header("WWW-Authenticate", "Bearer");
        }
        return response.body(exception.body());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorBody> uploadLimit(MaxUploadSizeExceededException exception) {
        return api(ApiException.tooLarge("Upload exceeds the 10 MiB file or 11 MiB multipart-request limit."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorBody> invalidFields(MethodArgumentNotValidException exception) {
        var details = exception.getBindingResult().getFieldErrors().stream().limit(32)
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList();
        return ResponseEntity.badRequest()
                .body(new ApiErrorBody("INVALID_REQUEST", "Correct the invalid request fields and try again.", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorBody> invalidJson(HttpMessageNotReadableException exception) {
        Throwable cause = exception;
        for (int depth = 0; depth < 12 && cause != null; depth++, cause = cause.getCause()) {
            if (cause instanceof ApiException apiException) {
                return api(apiException);
            }
        }
        return badRequest("The JSON body is missing or invalid. Use the documented fields and types; unknown or immutable fields are not accepted.");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiErrorBody> missingPart(Exception exception) {
        return badRequest("The required file part or request parameter is missing. Import requests must use multipart/form-data with a file field.");
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiErrorBody> invalidParameter(Exception exception) {
        return badRequest("A request value is invalid. Template identifiers must be valid UUIDs.");
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiErrorBody> invalidMultipart(MultipartException exception) {
        return badRequest("The multipart upload could not be read. Send one file part and an optional name.");
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiErrorBody> concurrentWrite(PessimisticLockingFailureException exception) {
        return api(ApiException.conflict());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorBody> missingRoute(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorBody("NOT_FOUND", "API route not found.", List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorBody> unsupportedMedia(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiErrorBody("UNSUPPORTED_MEDIA_TYPE",
                        "Use application/json for edits and multipart/form-data for spreadsheet imports.", List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorBody> unsupportedMethod(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiErrorBody("METHOD_NOT_ALLOWED", "This HTTP method is not supported by the API route.", List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorBody> unexpected(Exception exception) {
        return ResponseEntity.internalServerError().body(internalError(exception));
    }

    public static ApiErrorBody internalError(Throwable exception) {
        String reference = UUID.randomUUID().toString();
        // Exception messages can contain SQL values, request data or connection details; log only safe frames.
        LOG.error("Unexpected server error reference={} type={} frames={}", reference,
                exception.getClass().getName(), Arrays.stream(exception.getStackTrace()).limit(12).toList());
        return new ApiErrorBody("INTERNAL_ERROR", "An unexpected server error occurred. Please retry.",
                List.of("Support reference: " + reference));
    }

    private static ResponseEntity<ApiErrorBody> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ApiErrorBody("INVALID_REQUEST", message, List.of()));
    }
}
