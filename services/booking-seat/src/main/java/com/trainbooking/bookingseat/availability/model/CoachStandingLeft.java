package com.trainbooking.bookingseat.availability.model;

import com.trainbooking.bookingseat.common.CoachClass;

/** The standing places of one coach: its capacity, and how many can still be taken for the whole journey. */
// "remaining" is the minimum over the journey's segments (see SegmentAvailability.remainingForJourney).
public record CoachStandingLeft(long coachId, CoachClass coachClass, int capacity, int remaining) {
}
