package com.trainbooking.trainschedule.common;

import java.util.List;

/**
 * A request that is well-formed JSON but breaks a rule that Bean Validation cannot express on its own, for example a
 * duplicate inside a list of seats. Answered as 400 with the same {@code validation_failed} shape as field errors.
 */
// RuntimeException = unchecked: no "throws" clause needed. Thrown by ListValidation and some services, answered by
// GlobalExceptionHandler.invalidRequest as 400 validation_failed.
public class RequestValidationException extends RuntimeException {

    /** One entry per problem. "transient" because exceptions are Serializable and the list element type is not. */
    private final transient List<ApiError.Detail> details;

    /** @param details the problems; copied, so later changes by the caller cannot alter the exception */
    public RequestValidationException(List<ApiError.Detail> details) {
        super("The request body is not valid.");
        this.details = List.copyOf(details);
    }

    /** The field problems, sent to the client in {@code error.details}. */
    public List<ApiError.Detail> details() {
        return details;
    }
}
