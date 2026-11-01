package com.trainbooking.bookingseat.common;

import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.HoldExpiredException;
import com.trainbooking.bookingseat.payment.PaymentUnavailableException;
import com.trainbooking.bookingseat.booking.model.HoldConflictException;
import com.trainbooking.bookingseat.booking.model.TripClosedException;
import com.trainbooking.bookingseat.snapshot.model.SnapshotConflictException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every failure inside a controller into the shared JSON error shape. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- 400: the request itself is not acceptable -------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        List<ApiError.Detail> details = FieldProblems.fromFieldErrors(e.getBindingResult().getFieldErrors(), FieldProblems::jsonName);
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("validation_failed", "The request body is not valid.", details));
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ApiError> invalidRequest(RequestValidationException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("validation_failed", e.getMessage(), e.details()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody() {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("malformed_request", "The request body is missing or is not valid JSON."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badParameter(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("invalid_parameter", "The parameter '" + e.getName() + "' is not valid."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("missing_parameter", "The parameter '" + e.getParameterName() + "' is required."));
    }

    // ---- 404: the thing does not exist -------------------------------------------------------------------------

    @ExceptionHandler(TripNotFoundException.class)
    ResponseEntity<ApiError> tripNotFound(TripNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("trip_not_found", e.getMessage()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    ResponseEntity<ApiError> bookingNotFound(BookingNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("booking_not_found", e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute() {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("not_found", "No such route."));
    }

    // ---- 409: the request clashes with existing data ----------------------------------------------------------

    /** FR-H2: nothing was held, and the answer lists the items that were lost. */
    @ExceptionHandler(HoldConflictException.class)
    ResponseEntity<ApiError> holdConflict(HoldConflictException e) {
        List<ApiError.Detail> details = e.lost().stream()
                .map(lost -> new ApiError.Detail(lost.type().toLowerCase() + ":" + lost.id(), lost.label() + " is no longer available")).toList();
        return respond(HttpStatus.CONFLICT, ApiError.of("seats_unavailable", e.getMessage(), details));
    }

    @ExceptionHandler(BookingConflictException.class)
    ResponseEntity<ApiError> bookingConflict(BookingConflictException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of(e.code(), e.getMessage()));
    }

    /** SRS error contract: 410 Gone when acting on an expired hold. */
    @ExceptionHandler(HoldExpiredException.class)
    ResponseEntity<ApiError> holdExpired(HoldExpiredException e) {
        return respond(HttpStatus.GONE, ApiError.of("hold_expired", e.getMessage()));
    }

    @ExceptionHandler(PaymentUnavailableException.class)
    ResponseEntity<ApiError> paymentUnavailable(PaymentUnavailableException e) {
        log.warn("Payment unavailable: {}", e.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ApiError.of("payment_unavailable", "The payment service is not available right now. Try again in a moment."));
    }

    @ExceptionHandler(TripClosedException.class)
    ResponseEntity<ApiError> tripClosed(TripClosedException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(SnapshotConflictException.class)
    ResponseEntity<ApiError> snapshotConflict(SnapshotConflictException e) {
        ApiError body = e.details().isEmpty() ? ApiError.of(e.code(), e.getMessage()) : ApiError.of(e.code(), e.getMessage(), e.details());
        return respond(HttpStatus.CONFLICT, body);
    }

    // ---- everything else ---------------------------------------------------------------------------------------

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
