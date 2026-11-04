package com.trainbooking.trainschedule.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The one JSON error shape of every service and of the Gateway:
 * {@code { "error": { "code": "...", "message": "...", "details": [ ... ] } }}.
 * {@code details} is only present for validation errors.
 */
// A record is an immutable data class: the compiler writes the constructor, getters, equals and toString for us.
// Jackson turns it into JSON using the component names. Built only by GlobalExceptionHandler and the security error handlers.
public record ApiError(Body error) {

    /** The inner object. {@code code} is a stable machine word (for example station_not_found); {@code message} is for people. */
    // @JsonInclude(NON_NULL): leave "details" out of the JSON when it is null (it is only set for validation-style errors).
    public record Body(String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) List<Detail> details) {
    }

    /** One problem with one field, for example {@code field = "code"}, {@code message = "is required"}. */
    public record Detail(String field, String message) {
    }

    /** An error without details. */
    public static ApiError of(String code, String message) {
        return new ApiError(new Body(code, message, null));
    }

    /** An error with a list of field problems (or, for route_fares_incomplete, the list of missing fares). */
    public static ApiError of(String code, String message, List<Detail> details) {
        return new ApiError(new Body(code, message, details));
    }
}
