package com.trainbooking.notification.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The one JSON error shape of every service and of the Gateway:
 * {@code { "error": { "code": "...", "message": "...", "details": [ ... ] } }}.
 * {@code details} is only present for validation errors.
 */
// A record is an immutable data class; Jackson writes its fields as JSON. Used for every error body so clients parse one shape.
public record ApiError(Body error) {

    public record Body(String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) List<Detail> details) {
    }

    // One field-level problem, for example field "to" with message "must be an email address".
    public record Detail(String field, String message) {
    }

    // Factory without details (the "details" key is then left out of the JSON because of NON_NULL above).
    public static ApiError of(String code, String message) {
        return new ApiError(new Body(code, message, null));
    }

    // Factory with the list of field problems, used for 400 validation_failed.
    public static ApiError of(String code, String message, List<Detail> details) {
        return new ApiError(new Body(code, message, details));
    }
}
