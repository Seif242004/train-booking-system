package com.trainbooking.trainschedule.trip.model;

/** No trip has this id. Answered as 404 trip_not_found. */
public class TripNotFoundException extends RuntimeException {

    public TripNotFoundException(long id) {
        super("Trip " + id + " does not exist.");
    }
}
