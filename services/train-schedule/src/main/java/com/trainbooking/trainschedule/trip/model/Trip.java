package com.trainbooking.trainschedule.trip.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A trip as returned by the admin API (SRS section 8: id, train_id, route_id, departure_date, status), with the train and
 * route names for display and the stops in travel order.
 */
public record Trip(
        long id,
        long trainId,
        String trainNumber,
        String trainName,
        long routeId,
        String routeName,
        LocalDate departureDate,
        TripStatus status,
        List<TripStop> stops) {
}
