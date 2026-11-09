package com.trainbooking.ticket.common;

import java.util.List;

/**
 * A request that is well-formed JSON but breaks a rule that Bean Validation cannot express on its own, for example a
 * duplicate inside a list of seats. Answered as 400 with the same {@code validation_failed} shape as field errors.
 */
public class RequestValidationException extends RuntimeException {

    // {@code transient}: the list is not part of the serialized form of the exception (exceptions are Serializable by default).
    private final transient List<ApiError.Detail> details;

    // Copies the list so later changes by the caller cannot alter the error. Currently this service never throws this exception: it is the
    // shared error type of the services, kept so the error shape is the same everywhere.
    public RequestValidationException(List<ApiError.Detail> details) {
        super("The request body is not valid.");
        this.details = List.copyOf(details);
    }

    // The failing fields, for a handler that writes the 400 answer.
    public List<ApiError.Detail> details() {
        return details;
    }
}
