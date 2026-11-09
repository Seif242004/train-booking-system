package com.trainbooking.ticket.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that tests move by hand, to test retries and timeouts without waiting. */
public class MutableClock extends Clock {

    /** A fixed start, so that backoff and timeouts can be tested by moving time. */
    public static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

    // AtomicReference: safe when a test moves the time while other threads read it.
    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    public void set(Instant instant) {
        now.set(instant);
    }

    // Moves the time forward, to test timeouts and backoff without waiting. (The Ticket service has no timers; the class is shared by all
    // services.)
    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
