package com.trainbooking.bookingseat.outbox.service;

import com.trainbooking.bookingseat.outbox.model.OutboxMessage;

/**
 * The work of one kind of outbox message. {@link #run} may be repeated until it succeeds, so it must be idempotent: the calls it makes
 * (Ticket, Notification) are, by their keys. It runs outside any database transaction.
 */
public interface OutboxJob {

    OutboxMessage.Kind kind();

    /** @throws RuntimeException if the job could not be done now: the dispatcher tries again later with a backoff */
    void run(OutboxMessage message);

    /** True for a job that must never be given up (a refund): it is retried at the longest backoff for as long as it takes. */
    default boolean retryForever() {
        return false;
    }

    /** Called after every failed attempt ({@code attempt} counts from 1), for a job that has to leave a trace when it keeps failing. */
    default void onFailure(OutboxMessage message, int attempt, RuntimeException failure) {
    }
}
