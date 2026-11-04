package com.trainbooking.bookingseat.booking.model;

/** No such booking, or it belongs to someone else: the two look the same on purpose, so that its existence is not revealed (404). */
// 404 booking_not_found. Owner checks throw this for other people's bookings too (see BookingQueryService.viewOwn).
public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(long id) {
        super("Booking " + id + " does not exist.");
    }
}
