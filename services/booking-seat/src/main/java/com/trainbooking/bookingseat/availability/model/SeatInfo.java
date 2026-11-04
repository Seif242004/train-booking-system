package com.trainbooking.bookingseat.availability.model;

import com.trainbooking.bookingseat.common.CoachClass;

/** A seat of the trip as Booking keeps it: which coach it is in and its class. */
// Read from trip_seat (Booking's own copy of the seat, never a join to Train and Schedule).
public record SeatInfo(long seatId, long coachId, CoachClass coachClass) {
}
