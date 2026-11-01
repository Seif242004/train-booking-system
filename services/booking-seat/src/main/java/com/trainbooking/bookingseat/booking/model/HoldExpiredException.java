package com.trainbooking.bookingseat.booking.model;

/**
 * The 5 minutes of the hold are over (SRS: 410 Gone). A pick or a payment on it is refused even though the sweeper may not have
 * released the seats yet, and the timer is never extended: the user starts again from the seat map.
 */
public class HoldExpiredException extends RuntimeException {

    public HoldExpiredException() {
        super("Your 5 minutes ran out. The seats were released, please select again.");
    }
}
