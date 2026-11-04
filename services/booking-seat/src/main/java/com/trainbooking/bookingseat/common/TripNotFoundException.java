package com.trainbooking.bookingseat.common;

/** There is no copy of this trip in Booking and Seat: it was never pushed by Train and Schedule, or it was deleted. */
// Mapped to 404 trip_not_found by GlobalExceptionHandler.
public class TripNotFoundException extends RuntimeException {

    public TripNotFoundException(long tripId) {
        super("Trip " + tripId + " does not exist.");
    }
}
