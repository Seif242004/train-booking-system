package com.trainbooking.bookingseat.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The one JSON error shape of every service and of the Gateway:
 * {@code { "error": { "code": "...", "message": "...", "details": [ ... ] } }}.
 * {@code details} is only present for validation errors.
 */
public record ApiError(Body error) {

    public record Body(String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) List<Detail> details) {
    }

    public record Detail(String field, String message) {
    }

    public static ApiError of(String code, String message) {
        return new ApiError(new Body(code, message, null));
    }

    public static ApiError of(String code, String message, List<Detail> details) {
        return new ApiError(new Body(code, message, details));
    }
}
