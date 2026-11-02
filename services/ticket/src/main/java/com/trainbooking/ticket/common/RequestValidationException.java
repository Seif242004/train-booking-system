package com.trainbooking.ticket.common;

import java.util.List;

/**
 * A request that is well-formed JSON but breaks a rule that Bean Validation cannot express on its own, for example a
 * duplicate inside a list of seats. Answered as 400 with the same {@code validation_failed} shape as field errors.
 */
public class RequestValidationException extends RuntimeException {

    private final transient List<ApiError.Detail> details;

    public RequestValidationException(List<ApiError.Detail> details) {
        super("The request body is not valid.");
        this.details = List.copyOf(details);
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
