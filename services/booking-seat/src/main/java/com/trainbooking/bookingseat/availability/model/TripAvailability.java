package com.trainbooking.bookingseat.availability.model;

import com.trainbooking.bookingseat.common.CoachClass;
import java.math.BigDecimal;
import java.util.Map;

/** The availability of one trip for one journey, with the status of the trip and the price of the journey per class. {@code version} is the change counter of the trip (SRS section 6). */
public record TripAvailability(long tripId, String tripStatus, JourneyAvailability availability, Map<CoachClass, BigDecimal> fares, long version) {
}
