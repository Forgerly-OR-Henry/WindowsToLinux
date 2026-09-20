package gold.debug.windowstolinux.web.api.error;

import java.util.NoSuchElementException;
import java.util.UUID;

/** Stable public failures; never serializes exception messages, stack traces, paths or SQL. */
public record WebErrorResponse(String code, String message, String correlationId) {
    public static WebErrorResponse from(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.web.server.ResponseStatusException response)
                return http(response.getStatusCode().value());
        }
        if (failure instanceof org.springframework.web.HttpRequestMethodNotSupportedException) return http(405);
        if (failure instanceof org.springframework.web.HttpMediaTypeNotSupportedException) return http(415);
        if (failure instanceof org.springframework.web.servlet.resource.NoResourceFoundException) return http(404);
        if (failure instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || failure instanceof org.springframework.beans.TypeMismatchException
                || failure instanceof org.springframework.web.bind.ServletRequestBindingException) return http(400);
        String code = failure instanceof NoSuchElementException ? "NOT_FOUND"
                : failure instanceof IllegalArgumentException ? "INVALID_INPUT"
                : failure instanceof IllegalStateException || failure instanceof org.springframework.dao.OptimisticLockingFailureException
                || failure instanceof org.springframework.dao.DataIntegrityViolationException ? "STATE_CONFLICT"
                : failure instanceof SecurityException ? "REQUEST_REJECTED" : "OPERATION_FAILED";
        String message = switch (code) {
            case "NOT_FOUND" -> "Resource not found. Refresh the list.";
            case "INVALID_INPUT" -> "Input validation failed. Check fields, file format and limits.";
            case "STATE_CONFLICT" -> "Resource changed or task queue is full. Refresh and retry.";
            case "REQUEST_REJECTED" -> "Request origin or access boundary was rejected.";
            default -> "Operation failed. Check configuration and connectivity.";
        };
        return new WebErrorResponse(code, message, UUID.randomUUID().toString());
    }
    public static WebErrorResponse http(int status) {
        String code = switch (status) {
            case 400 -> "INVALID_INPUT"; case 403 -> "REQUEST_REJECTED"; case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED"; case 413 -> "REQUEST_TOO_LARGE"; case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "SERVER_BUSY"; default -> "OPERATION_FAILED";
        };
        String message = switch (status) {
            case 400 -> "Invalid request body or parameters."; case 403 -> "Request origin or access boundary was rejected.";
            case 404 -> "Resource not found."; case 405 -> "HTTP method is not supported for this resource.";
            case 413 -> "Request exceeds the configured size limit."; case 415 -> "Unsupported request content type.";
            case 503 -> "Request capacity is full. Retry later."; default -> "Operation failed. Check configuration and connectivity.";
        };
        return new WebErrorResponse(code, message, UUID.randomUUID().toString());
    }
    public int status() {
        return switch (code) {
            case "NOT_FOUND" -> 404; case "INVALID_INPUT" -> 400; case "STATE_CONFLICT" -> 409; case "REQUEST_REJECTED" -> 403;
            case "METHOD_NOT_ALLOWED" -> 405; case "REQUEST_TOO_LARGE" -> 413; case "UNSUPPORTED_MEDIA_TYPE" -> 415;
            case "SERVER_BUSY" -> 503; default -> 500;
        };
    }
}
