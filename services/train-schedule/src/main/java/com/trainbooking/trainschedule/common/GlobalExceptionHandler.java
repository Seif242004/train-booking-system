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

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute() {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("not_found", "No such route."));
    }

    // ---- 409: the request clashes with existing data ----------------------------------------------------------

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
