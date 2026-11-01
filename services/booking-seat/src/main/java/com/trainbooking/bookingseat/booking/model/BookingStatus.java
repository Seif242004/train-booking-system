package com.trainbooking.bookingseat.booking.model;

/** The states of a booking (SRS 4.2). The refund is tracked separately in refund_status, not here. */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    EXPIRED,
    CANCELLED,
    ABANDONED
}
