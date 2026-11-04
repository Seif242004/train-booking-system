package com.trainbooking.bookingseat.booking.model;

/**
 * How a booking was paid: by card through Stripe, in person at the counter, or not at all (complimentary). See
 * docs/design/COUNTER-BOOKING.md.
 */
// Stored in booking.payment_method (V8, check constraint ck_booking_method).
public enum PaymentMethod {
    CARD,
    COUNTER,
    COMPLIMENTARY;

    /** An admin-issued booking is paid at the desk, or free: Payment and Stripe are never involved. */
    public boolean issuedByAdmin() {
        return this != CARD;
    }
}
