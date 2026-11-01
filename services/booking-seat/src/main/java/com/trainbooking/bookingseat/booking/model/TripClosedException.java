package com.trainbooking.bookingseat.booking.model;

/** The trip cannot take holds any more: it is being cancelled or is cancelled ({@code trip_closed}), or it left ({@code trip_departed}). 409. */
public class TripClosedException extends RuntimeException {

    private final String code;

    public TripClosedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
