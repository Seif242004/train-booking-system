package com.trainbooking.notification.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every failure inside a controller into the shared JSON error shape. */
// @RestControllerAdvice: one class that handles exceptions thrown by any controller and writes the result as JSON.
// Each @ExceptionHandler below names the exception type it answers. Handlers are package-private because Spring calls them by reflection.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Bean Validation failed on @Valid @RequestBody (see NotificationRequest): 400 validation_failed with one detail per field (first
    // message only).
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        Map<String, String> byField = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            byField.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        List<ApiError.Detail> details = byField.entrySet().stream().map(entry -> new ApiError.Detail(entry.getKey(), entry.getValue())).toList();
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("validation_failed", "The request body is not valid.", details));
    }

    // The body is missing or not parseable JSON (or has a wrong type, such as an unknown kind): 400 malformed_request.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody() {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("malformed_request", "The request body is missing or is not valid JSON."));
    }

    // Nothing matches the URL: 404 not_found. (Under /internal an unknown path still needs the API key first.)
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute() {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("not_found", "No such route."));
    }

    // A known path with the wrong HTTP method (for example GET /internal/notifications): 405 method_not_allowed.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> wrongMethod() {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of("method_not_allowed", "This method is not supported for this route."));
    }

    // Safety net for anything unexpected: the stack trace goes to the log, the client only gets a generic 500 so no internals leak.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of("internal_error", "Something went wrong."));
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
