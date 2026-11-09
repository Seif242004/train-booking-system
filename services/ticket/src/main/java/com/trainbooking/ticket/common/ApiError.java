package com.trainbooking.ticket.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The one JSON error shape of every service and of the Gateway:
 * {@code { "error": { "code": "...", "message": "...", "details": [ ... ] } }}.
 * {@code details} is only present for validation errors.
 */
// A record is an immutable data class: the compiler writes the constructor, getters, equals and toString. Jackson turns it into JSON.
public record ApiError(Body error) {

    // {@code @JsonInclude(NON_NULL)} leaves the "details" key out of the JSON when it is null (only validation errors have details).
    public record Body(String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) List<Detail> details) {
    }

    // One failing field of a request body: its name and a short message.
    public record Detail(String field, String message) {
    }

    // An error without details: used for every error except validation failures.
    public static ApiError of(String code, String message) {
        return new ApiError(new Body(code, message, null));
    }

    // An error with the list of failing fields (400 validation_failed).
    public static ApiError of(String code, String message, List<Detail> details) {
        return new ApiError(new Body(code, message, details));
    }
}
