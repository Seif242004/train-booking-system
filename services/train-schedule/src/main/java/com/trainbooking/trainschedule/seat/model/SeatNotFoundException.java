package com.trainbooking.trainschedule.seat.model;

/** No seat has this id. Answered as 404 seat_not_found. */
public class SeatNotFoundException extends RuntimeException {

    public SeatNotFoundException(long id) {
        super("Seat " + id + " does not exist.");
    }
}
