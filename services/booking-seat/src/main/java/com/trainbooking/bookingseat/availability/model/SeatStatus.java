package com.trainbooking.bookingseat.availability.model;

import com.trainbooking.bookingseat.common.CoachClass;

/** The state of one seat for the journey that was asked about. */
// SeatInfo plus the computed state for the journey asked about.
public record SeatStatus(long seatId, long coachId, CoachClass coachClass, SeatState state) {
}
