package com.trainbooking.trainschedule.trip.model;

public class TripNotFoundException extends RuntimeException {

    public TripNotFoundException(long id) {
        super("Trip " + id + " does not exist.");
    }
}
