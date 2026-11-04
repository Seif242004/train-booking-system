package com.trainbooking.bookingseat.booking.model;

/** How a booking was paid: by card through Stripe, in person at the counter, or not at all (complimentary). See docs/design/COUNTER-BOOKING.md. */
public enum PaymentMethod {
    CARD,
    COUNTER,
    COMPLIMENTARY;

    /** An admin-issued booking is paid at the desk, or free: Payment and Stripe are never involved. */
    public boolean issuedByAdmin() {
        return this != CARD;
    }
}
