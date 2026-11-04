package com.trainbooking.bookingseat.common;

/**
 * A call to another service (Ticket, Notification) got no usable answer: no connection, a timeout, a 5xx, or a refusal. The outbox keeps
 * the job and tries again later, so this never reaches a user.
 */
// Unchecked (extends RuntimeException) so it can cross the OutboxJob interface; the dispatcher catches it and schedules the next try.
public class RemoteServiceException extends RuntimeException {

    public RemoteServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    public RemoteServiceException(String message) {
        super(message);
    }
}
