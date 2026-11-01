package com.trainbooking.bookingseat.booking.model;

/**
 * The request clashes with the state of the booking: a pick for another journey while the user holds seats for one, too many items
 * in one hold, a payment that is already made or being made. Answered as 409 with the code, so that the page can say what to do.
 */
public class BookingConflictException extends RuntimeException {

    private final String code;

    public BookingConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
