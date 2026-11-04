package com.trainbooking.trainschedule.station.model;

/** No station has this id. Answered as 404 station_not_found. */
public class StationNotFoundException extends RuntimeException {

    public StationNotFoundException(long id) {
        super("Station " + id + " does not exist.");
    }
}
