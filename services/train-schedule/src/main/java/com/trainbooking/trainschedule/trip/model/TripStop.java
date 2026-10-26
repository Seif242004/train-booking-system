package com.trainbooking.trainschedule.trip.model;

import java.time.LocalDateTime;

/**
 * One stop of a trip as returned by the API (SRS section 8: stop_index, station_id, arrival_time, departure_time) plus
 * the station's code and name. The times are local date-times in the service time zone. The first stop has no arrival
 * time and the last stop has no departure time.
 */
public record TripStop(
        int stopIndex,
        long stationId,
        String stationCode,
        String stationName,
        LocalDateTime arrivalTime,
        LocalDateTime departureTime) {
}
