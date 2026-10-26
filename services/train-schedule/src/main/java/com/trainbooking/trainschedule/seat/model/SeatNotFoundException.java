package com.trainbooking.trainschedule.seat.model;

public class SeatNotFoundException extends RuntimeException {

    public SeatNotFoundException(long id) {
        super("Seat " + id + " does not exist.");
    }
}
