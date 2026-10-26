package com.trainbooking.trainschedule.common;

import com.trainbooking.trainschedule.station.StationCodeTakenException;
import com.trainbooking.trainschedule.station.StationNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every failure inside a controller into the shared JSON error shape. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        List<ApiError.Detail> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.Detail(error.getField(), error.getDefaultMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("validation_failed", "The request body is not valid.", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody() {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("malformed_request", "The request body is missing or is not valid JSON."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badParameter(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("invalid_parameter", "The parameter '" + e.getName() + "' is not valid."));
    }

    @ExceptionHandler(StationNotFoundException.class)
    ResponseEntity<ApiError> stationNotFound(StationNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("station_not_found", e.getMessage()));
    }

    @ExceptionHandler(StationCodeTakenException.class)
    ResponseEntity<ApiError> stationCodeTaken(StationCodeTakenException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("station_code_taken", e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute() {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("not_found", "No such route."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> wrongMethod() {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of("method_not_allowed", "This method is not supported for this route."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of("internal_error", "Something went wrong."));
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
