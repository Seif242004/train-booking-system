package com.trainbooking.bookingseat.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The one JSON error shape of every service and of the Gateway:
 * {@code { "error": { "code": "...", "message": "...", "details": [ ... ] } }}.
 * {@code details} is only present for validation errors.
 */
// A Java record is an immutable data class: the compiler creates the constructor, getters and equals/hashCode. Jackson turns it into JSON.
public record ApiError(Body error) {

    // @JsonInclude(NON_NULL): the "details" field is left out of the JSON when it is null, so plain errors have only code and message.
    public record Body(String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) List<Detail> details) {
    }

    public record Detail(String field, String message) {
    }

    /** An error without details (every error except validation and "seats lost" errors). */
    public static ApiError of(String code, String message) {
        return new ApiError(new Body(code, message, null));
    }

    /** An error with one detail per field or per lost item. */
    public static ApiError of(String code, String message, List<Detail> details) {
        return new ApiError(new Body(code, message, details));
    }
}
