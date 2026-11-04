package com.trainbooking.bookingseat.availability.model;

import com.trainbooking.bookingseat.common.CoachClass;

/** One row of standing_usage: the capacity of a coach and the places used on one segment. */
// One row of standing_usage: (trip, coach, segment) with its capacity and the places in use. The conditional UPDATEs of
// InventoryRepository keep used between 0 and capacity (the table also has a CHECK for it).
public record StandingInfo(long coachId, CoachClass coachClass, int segmentIndex, int capacity, int used) {
}
