package com.trainbooking.bookingseat.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that tests move by hand, to test the expiry of holds without waiting five minutes. */
public class MutableClock extends Clock {

    /** The day before the demo trip leaves (2026-11-02 08:00 Cairo time), so the trip has not departed. */
    public static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    public void set(Instant instant) {
        now.set(instant);
    }

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
