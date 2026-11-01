package com.trainbooking.trainschedule.trip.model;

/**
 * Booking and Seat could not be reached, timed out, or answered with an error that is not about the snapshot. A trip is
 * committed only after Booking accepts it, so the trip is not saved. Answered as 503 {@code booking_unavailable}.
 */
public class BookingUnavailableException extends RuntimeException {

    public BookingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
