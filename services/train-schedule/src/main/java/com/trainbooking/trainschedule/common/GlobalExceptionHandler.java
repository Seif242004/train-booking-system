package com.trainbooking.trainschedule.common;

import com.trainbooking.trainschedule.coach.model.CoachCodeTakenException;
import com.trainbooking.trainschedule.coach.model.CoachNotFoundException;
import com.trainbooking.trainschedule.coach.model.CoachPositionTakenException;
import com.trainbooking.trainschedule.route.model.RouteHasFaresException;
import com.trainbooking.trainschedule.route.model.RouteInUseException;
import com.trainbooking.trainschedule.route.model.RouteNotFoundException;
import com.trainbooking.trainschedule.seat.model.SeatConflictException;
import com.trainbooking.trainschedule.seat.model.SeatNotFoundException;
import com.trainbooking.trainschedule.station.model.StationCodeTakenException;
import com.trainbooking.trainschedule.station.model.StationInUseException;
import com.trainbooking.trainschedule.station.model.StationNotFoundException;
import com.trainbooking.trainschedule.train.model.TrainInUseException;
import com.trainbooking.trainschedule.train.model.TrainNotFoundException;
import com.trainbooking.trainschedule.train.model.TrainNumberTakenException;
import com.trainbooking.trainschedule.trip.model.BookingConflictException;
import com.trainbooking.trainschedule.trip.model.BookingSnapshotRejectedException;
import com.trainbooking.trainschedule.trip.model.BookingUnavailableException;
import com.trainbooking.trainschedule.trip.model.RouteFaresIncompleteException;
import com.trainbooking.trainschedule.trip.model.TrainHasNoCoachesException;
import com.trainbooking.trainschedule.trip.model.TrainInactiveException;
import com.trainbooking.trainschedule.trip.model.TripNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure inside a controller into the shared JSON error shape.
 *
 * <p>Services throw small typed exceptions (for example {@code StationNotFoundException}); controllers never catch them.
 * This class is the single place that maps each exception to an HTTP status and a stable error {@code code}:
 * 400 bad request, 404 not found, 409 conflict with existing data, 502/503 Booking &amp; Seat problems, 500 anything else.
 * Security failures (401/403) happen earlier, in the filter chain, and are handled by {@code JsonErrorHandlers}.
 */
// @RestControllerAdvice: one class whose @ExceptionHandler methods apply to all controllers and write JSON bodies.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- 400: the request itself is not acceptable -------------------------------------------------------------

    /** A request body failed Bean Validation ({@code @Valid}): 400 with one detail per field. */
    // @ExceptionHandler(X.class): "when a controller throws X (or a subclass), run this method instead".
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        List<ApiError.Detail> details = FieldProblems.fromFieldErrors(e.getBindingResult().getFieldErrors(), FieldProblems::jsonName);
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("validation_failed", "The request body is not valid.", details));
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ApiError> invalidRequest(RequestValidationException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("validation_failed", e.getMessage(), e.details()));
    }

    /** Body missing or not parseable JSON (also an unknown enum value): 400 malformed_request. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody() {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("malformed_request", "The request body is missing or is not valid JSON."));
    }

    /** A path or query value of the wrong type, for example {@code /admin/trains/abc} or {@code ?date=notadate}: 400 invalid_parameter. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badParameter(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("invalid_parameter", "The parameter '" + e.getName() + "' is not valid."));
    }

    /** A required query parameter is absent (for example {@code from} in trip search): 400 missing_parameter. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("missing_parameter", "The parameter '" + e.getParameterName() + "' is required."));
    }

    // ---- 404: the thing does not exist -------------------------------------------------------------------------
    // Each *NotFoundException below is thrown by a service when an id is unknown; the handler keeps the exception's message.

    @ExceptionHandler(StationNotFoundException.class)
    ResponseEntity<ApiError> stationNotFound(StationNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("station_not_found", e.getMessage()));
    }

    @ExceptionHandler(TrainNotFoundException.class)
    ResponseEntity<ApiError> trainNotFound(TrainNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("train_not_found", e.getMessage()));
    }

    @ExceptionHandler(CoachNotFoundException.class)
    ResponseEntity<ApiError> coachNotFound(CoachNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("coach_not_found", e.getMessage()));
    }

    @ExceptionHandler(SeatNotFoundException.class)
    ResponseEntity<ApiError> seatNotFound(SeatNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("seat_not_found", e.getMessage()));
    }

    @ExceptionHandler(TripNotFoundException.class)
    ResponseEntity<ApiError> tripNotFound(TripNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("trip_not_found", e.getMessage()));
    }

    @ExceptionHandler(RouteNotFoundException.class)
    ResponseEntity<ApiError> routeNotFound(RouteNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("route_not_found", e.getMessage()));
    }

    /** No controller matches the URL at all. */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute() {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("not_found", "No such route."));
    }

    // ---- 409: the request clashes with existing data ----------------------------------------------------------
    // *TakenException: a unique value (code, number, position) already exists. *InUseException: other rows still refer to it.
    // train_inactive / train_has_no_coaches / route_fares_incomplete: a trip cannot be made yet (see TripService).
    // route_has_fares: changing a route's stops would orphan its fares. seat_conflict: bulk seat add clashes with existing seats.

    @ExceptionHandler(StationCodeTakenException.class)
    ResponseEntity<ApiError> stationCodeTaken(StationCodeTakenException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("station_code_taken", e.getMessage()));
    }

    @ExceptionHandler(StationInUseException.class)
    ResponseEntity<ApiError> stationInUse(StationInUseException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("station_in_use", e.getMessage()));
    }

    @ExceptionHandler(TrainInUseException.class)
    ResponseEntity<ApiError> trainInUse(TrainInUseException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("train_in_use", e.getMessage()));
    }

    @ExceptionHandler(RouteInUseException.class)
    ResponseEntity<ApiError> routeInUse(RouteInUseException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("route_in_use", e.getMessage()));
    }

    @ExceptionHandler(TrainInactiveException.class)
    ResponseEntity<ApiError> trainInactive(TrainInactiveException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("train_inactive", e.getMessage()));
    }

    @ExceptionHandler(TrainHasNoCoachesException.class)
    ResponseEntity<ApiError> trainHasNoCoaches(TrainHasNoCoachesException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("train_has_no_coaches", e.getMessage()));
    }

    @ExceptionHandler(RouteFaresIncompleteException.class)
    ResponseEntity<ApiError> routeFaresIncomplete(RouteFaresIncompleteException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("route_fares_incomplete", e.getMessage(), e.details()));
    }

    @ExceptionHandler(RouteHasFaresException.class)
    ResponseEntity<ApiError> routeHasFares(RouteHasFaresException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("route_has_fares", e.getMessage()));
    }

    @ExceptionHandler(BookingConflictException.class)
    ResponseEntity<ApiError> bookingConflict(BookingConflictException e) {
        return respond(HttpStatus.CONFLICT, e.details().isEmpty()
                ? ApiError.of("booking_conflict", e.getMessage()) : ApiError.of("booking_conflict", e.getMessage(), e.details()));
    }

    @ExceptionHandler(TrainNumberTakenException.class)
    ResponseEntity<ApiError> trainNumberTaken(TrainNumberTakenException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("train_number_taken", e.getMessage()));
    }

    @ExceptionHandler(CoachCodeTakenException.class)
    ResponseEntity<ApiError> coachCodeTaken(CoachCodeTakenException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("coach_code_taken", e.getMessage()));
    }

    @ExceptionHandler(CoachPositionTakenException.class)
    ResponseEntity<ApiError> coachPositionTaken(CoachPositionTakenException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("coach_position_taken", e.getMessage()));
    }

    @ExceptionHandler(SeatConflictException.class)
    ResponseEntity<ApiError> seatConflict(SeatConflictException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of("seat_conflict", e.getMessage(), e.details()));
    }

    // ---- 502 and 503: Booking and Seat, which keeps its own copy of every trip, did not accept the change ------------
    // The edit is pushed to Booking as the last step before the commit, so in all three cases below nothing was saved:
    // 409 booking_conflict = Booking refused with a conflict (holds or bookings exist); 502 = Booking refused for another reason;
    // 503 = Booking could not be reached (timeout or connection error).

    @ExceptionHandler(BookingSnapshotRejectedException.class)
    ResponseEntity<ApiError> bookingRejected(BookingSnapshotRejectedException e) {
        return respond(HttpStatus.BAD_GATEWAY, e.details().isEmpty()
                ? ApiError.of("booking_rejected_snapshot", e.getMessage()) : ApiError.of("booking_rejected_snapshot", e.getMessage(), e.details()));
    }

    @ExceptionHandler(BookingUnavailableException.class)
    ResponseEntity<ApiError> bookingUnavailable() {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ApiError.of("booking_unavailable",
                "Booking and Seat could not be reached, so the change was not saved. Try again in a moment."));
    }

    // ---- everything else ---------------------------------------------------------------------------------------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> wrongMethod() {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of("method_not_allowed", "This method is not supported for this route."));
    }

    /** Safety net for any bug: logs the stack trace (once, here) and hides the details from the client. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of("internal_error", "Something went wrong."));
    }

    /** Small helper: status plus JSON body. */
    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
