package com.trainbooking.bookingseat.availability.model;

import com.trainbooking.bookingseat.common.CoachClass;

/** A seat of the trip as Booking keeps it: which coach it is in and its class. */
public record SeatInfo(long seatId, long coachId, CoachClass coachClass) {
}
