package com.trainbooking.bookingseat.live;

import java.util.List;

/**
 * A change of the availability of a trip, for everyone who is looking at it (SRS section 6). It is published inside the
 * transaction of the change and delivered only after that transaction commits, so a client never sees uncommitted state.
 * {@code version} is the per-trip counter, increased once for every event: a client that sees a gap has missed an event and
 * reloads the seat map.
 *
 * @param type     SEAT_HELD, SEAT_RELEASED, SEAT_BOOKED, STANDING_CHANGED or TRIP_CANCELLED
 * @param seatIds  the seats, for the seat events (empty otherwise)
 * @param coachId  the coach, for STANDING_CHANGED (null otherwise)
 * @param delta    the change of the standing places left, for STANDING_CHANGED (negative when places are taken)
 * @param segments the segments the change applies to; a client ignores the event if they do not overlap its journey
 */
public record SeatEvent(String type, long tripId, List<Long> seatIds, Long coachId, Integer delta, List<Integer> segments, long version) {

    public static final String SEAT_HELD = "SEAT_HELD";
    public static final String SEAT_RELEASED = "SEAT_RELEASED";
    public static final String SEAT_BOOKED = "SEAT_BOOKED";
    public static final String STANDING_CHANGED = "STANDING_CHANGED";
    public static final String TRIP_CANCELLED = "TRIP_CANCELLED";
}
