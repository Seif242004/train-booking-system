package com.trainbooking.bookingseat.availability.model;

import com.trainbooking.bookingseat.common.CoachClass;
import java.math.BigDecimal;

/** The fare of one segment for one class, from Booking's copy of the trip (trip_fare). The currency is always EGP. */
// Read from trip_fare, a copy of the fares that Train and Schedule put in the snapshot. Frozen once a reservation exists.
public record FareInfo(int segmentIndex, CoachClass coachClass, BigDecimal amount) {
}
