package com.trainbooking.ticket.common;

import com.trainbooking.ticket.ticket.model.TicketNotFoundException;
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
// {@code @RestControllerAdvice} makes the class a handler shared by all controllers: its methods catch exceptions thrown anywhere in
// a request and write the answer as JSON. The controllers and the service therefore never build error answers themselves.
// Spring picks the most specific {@code @ExceptionHandler} for the thrown type, so the general Exception handler below is the last resort.
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Used only for the unexpected case: the stack trace goes to the log, the client never sees it.
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // {@code @ExceptionHandler(X.class)} marks the method that answers when X (or a subclass) is thrown.
    // Here: @Valid on the request body failed. One detail per field; putIfAbsent keeps only the first message of each field. HTTP 400.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        Map<String, String> byField = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            byField.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        List<ApiError.Detail> details = byField.entrySet().stream().map(entry -> new ApiError.Detail(entry.getKey(), entry.getValue())).toList();
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("validation_failed", "The request body is not valid.", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    // The body is missing, is not JSON, or has a value of the wrong type (for example a text where a number is expected). HTTP 400.
    ResponseEntity<ApiError> unreadableBody() {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("malformed_request", "The request body is missing or is not valid JSON."));
    }

    @ExceptionHandler(TicketNotFoundException.class)
    // No ticket for that booking id. HTTP 404 with the code ticket_not_found.
    ResponseEntity<ApiError> notFound(TicketNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("ticket_not_found", e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    // A path that no controller serves (inside /internal): HTTP 404 in the shared error shape instead of the default page of Spring.
    ResponseEntity<ApiError> noRoute() {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("not_found", "No such route."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    // For example a DELETE on /internal/tickets/1. HTTP 405.
    ResponseEntity<ApiError> wrongMethod() {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of("method_not_allowed", "This method is not supported for this route."));
    }

    @ExceptionHandler(Exception.class)
    // Anything not listed above is a bug or an infrastructure failure (database down, PDF failure). HTTP 500 with a generic message, so
    // internal details never leak; the full error is logged.
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of("internal_error", "Something went wrong."));
    }

    // One place that builds the response, so every error has the same shape.
    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
