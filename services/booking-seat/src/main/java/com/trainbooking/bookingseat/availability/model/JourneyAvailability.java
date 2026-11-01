package com.trainbooking.bookingseat.availability.model;

import com.trainbooking.bookingseat.common.CoachClass;
import java.util.List;
import java.util.Map;

/**
 * The availability of a trip for one journey. {@code seatsLeft} and {@code standingLeft} are totals per class; a class is
 * listed only if the trip has seats (or standing coaches) of it.
 */
public record JourneyAvailability(
        Journey journey,
        List<SeatStatus> seats,
        List<CoachStandingLeft> standing,
        Map<CoachClass, Integer> seatsLeft,
        Map<CoachClass, Integer> standingLeft) {
}
