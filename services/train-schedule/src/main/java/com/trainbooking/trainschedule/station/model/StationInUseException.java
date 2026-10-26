package com.trainbooking.trainschedule.station.model;

/** The station is a stop of at least one route, so it cannot be deleted. Answered as 409 station_in_use. */
public class StationInUseException extends RuntimeException {

    public StationInUseException(long id) {
        super("Station " + id + " is a stop of a route, so it cannot be deleted. Remove it from its routes first.");
    }
}
