package com.trainbooking.bookingseat.availability.model;

import com.trainbooking.bookingseat.common.CoachClass;

/** One row of standing_usage: the capacity of a coach and the places used on one segment. */
public record StandingInfo(long coachId, CoachClass coachClass, int segmentIndex, int capacity, int used) {
}
