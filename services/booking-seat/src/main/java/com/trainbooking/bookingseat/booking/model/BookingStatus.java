package com.trainbooking.bookingseat.booking.model;

/** The states of a booking (SRS 4.2). The refund is tracked separately in refund_status, not here. */
// PENDING_PAYMENT = a live hold. CONFIRMED = paid (or issued by an admin). EXPIRED = the 5 minutes ran out or the trip was cancelled.
// CANCELLED = a confirmed booking that was cancelled. ABANDONED = the user released every item. Only PENDING_PAYMENT has seat reservations
// with an expiry; the check constraint ck_booking_status in V2 lists exactly these values.
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    EXPIRED,
    CANCELLED,
    ABANDONED
}
