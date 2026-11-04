package com.trainbooking.bookingseat.booking.model;

/** No passenger with exactly that email is known to this service. */
public class PassengerNotFoundException extends RuntimeException {

    public PassengerNotFoundException() {
        super("No passenger with that exact email is known.");
    }
}
