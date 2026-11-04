package com.trainbooking.bookingseat.common;

import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.HoldExpiredException;
import com.trainbooking.bookingseat.payment.PaymentRejectedException;
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
// @RestControllerAdvice = one class whose @ExceptionHandler methods apply to every controller of the service, and whose return values
// are written as JSON. It keeps the controllers free of try/catch and gives every error the same shape (ApiError) with a stable "code"
// that the Gateway and the frontend can test. HTTP status map: 400 bad input, 404 not found, 409 conflict, 410 expired hold,
// 503 payment service down, 500 unexpected. The 401 and 403 answers are produced earlier, by the security filters (see JsonErrorHandlers).
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- 400: the request itself is not acceptable -------------------------------------------------------------

    // @ExceptionHandler(X.class): this method answers every X thrown by a controller. Here: @Valid on a request body failed (field errors).
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        List<ApiError.Detail> details = FieldProblems.fromFieldErrors(e.getBindingResult().getFieldErrors(), FieldProblems::jsonName);
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("validation_failed", "The request body is not valid.", details));
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ApiError> invalidRequest(RequestValidationException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("validation_failed", e.getMessage(), e.details()));
    }

    // The body is missing or is not JSON (or has the wrong types).
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody() {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("malformed_request", "The request body is missing or is not valid JSON."));
    }

    // A query or path parameter could not be converted, for example ?from=abc where a number is expected.
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

    @ExceptionHandler(com.trainbooking.bookingseat.booking.model.PassengerNotFoundException.class)
    ResponseEntity<ApiError> passengerNotFound(com.trainbooking.bookingseat.booking.model.PassengerNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("passenger_not_found", e.getMessage()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    ResponseEntity<ApiError> bookingNotFound(BookingNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("booking_not_found", e.getMessage()));
    }

    // No controller matches the path: answered with the shared shape instead of Spring's default HTML error page.
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
    // A rule of the booking lifecycle was broken (for example not_cancellable, trip_departed, payment_in_progress,
    // hold_for_another_journey).
    // The exception carries the stable error code.
    ResponseEntity<ApiError> bookingConflict(BookingConflictException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of(e.code(), e.getMessage()));
    }

    /** SRS error contract: 410 Gone when acting on an expired hold. */
    @ExceptionHandler(HoldExpiredException.class)
    ResponseEntity<ApiError> holdExpired(HoldExpiredException e) {
        return respond(HttpStatus.GONE, ApiError.of("hold_expired", e.getMessage()));
    }

    // 503: the Payment service could not be reached or has no Stripe key. The hold stays untouched; the user can try again.
    @ExceptionHandler(PaymentUnavailableException.class)
    ResponseEntity<ApiError> paymentUnavailable(PaymentUnavailableException e) {
        log.warn("Payment unavailable: {}", e.getMessage());
        String message = "stripe_not_configured".equals(e.code())
                ? "Payments are not set up yet: this deployment has no Stripe key."
                : "The payment service is not available right now. Try again in a moment.";
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ApiError.of(e.code(), message));
    }

    /** Payment understood the request and refused it: the hold ended, already paid, a payment is being created... */
    @ExceptionHandler(PaymentRejectedException.class)
    ResponseEntity<ApiError> paymentRejected(PaymentRejectedException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of(e.code(), "The booking cannot be paid right now (" + e.code() + ")."));
    }

    @ExceptionHandler(TripClosedException.class)
    // The trip is CANCELLING, CANCELLED or has already left: no new holds (trip_closed / trip_departed).
    ResponseEntity<ApiError> tripClosed(TripClosedException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of(e.code(), e.getMessage()));
    }

    // Used by the internal snapshot endpoint: trip_not_open, trip_has_reservations, capacity_conflict (FR-A9), with a list of the items
    // in conflict.
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

    // The catch-all: the real cause goes to the log (with the stack trace), the client only gets a generic message so no internals leak.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of("internal_error", "Something went wrong."));
    }

    // Builds the HTTP response: the status line plus the ApiError JSON body.
    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
