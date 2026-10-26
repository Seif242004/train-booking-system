package com.trainbooking.trainschedule.trip.model;

import com.trainbooking.trainschedule.station.model.Station;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One trip that makes the searched journey. {@code fromStopIndex} and {@code toStopIndex} are the stops of the journey on
 * the trip's route (the journey uses segments fromStopIndex to toStopIndex - 1). {@code stops} are all the stops of the
 * trip with their times, so a client can show the stops inside and outside the journey. {@code fares} has the price per class.
 * TODO(slice 6): add the seats and standing places left for this journey, which Booking & Seat owns (FR-S2).
 */
public record TripSearchResult(
        long tripId,
        String trainNumber,
        String trainName,
        String trainType,
        String routeName,
        Station from,
        Station to,
        int fromStopIndex,
        int toStopIndex,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        List<TripStop> stops,
        List<JourneyFare> fares) {
}
