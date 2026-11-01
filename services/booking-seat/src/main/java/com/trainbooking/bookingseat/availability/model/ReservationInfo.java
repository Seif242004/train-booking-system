package com.trainbooking.bookingseat.availability.model;

/**
 * One row of seat_reservation: a seat taken on one segment. {@code held} is true while the row has an expires_at
 * (a hold) and false once the booking is confirmed. Whether the hold has already expired is deliberately not part of
 * this: an expired hold that the sweeper has not released yet still blocks the seat (CLAUDE.md, "5-minute hold").
 */
public record ReservationInfo(long seatId, int segmentIndex, boolean held) {
}
