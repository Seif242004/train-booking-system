package com.trainbooking.trainschedule.station.model;

public class StationNotFoundException extends RuntimeException {

    public StationNotFoundException(long id) {
        super("Station " + id + " does not exist.");
    }
}
