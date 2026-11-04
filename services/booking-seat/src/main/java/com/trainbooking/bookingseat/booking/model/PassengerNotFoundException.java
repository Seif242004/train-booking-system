package com.trainbooking.bookingseat.booking.model;

/** No passenger with exactly that email is known to this service. */
// 404 passenger_not_found: the exact-email lookup of an admin found nobody. Never a list, so emails cannot be harvested.
public class PassengerNotFoundException extends RuntimeException {

    public PassengerNotFoundException() {
        super("No passenger with that exact email is known.");
    }
}
