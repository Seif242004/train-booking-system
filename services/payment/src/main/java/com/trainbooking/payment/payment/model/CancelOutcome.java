package com.trainbooking.payment.payment.model;

/** What happened to the payment of a booking when Booking and Seat asked to cancel or invalidate it (SRS 4.2, FR-P4). */
public enum CancelOutcome {
    /** There was no payment: nothing to cancel. */
    NONE,
    /** The payment is cancelled (or was): nobody can pay it any more. */
    CANCELLED,
    /** The payment already succeeded: the payment wins. */
    SUCCEEDED,
    /** Stripe is still processing the payment: the caller keeps the seats held and asks again. */
    PROCESSING
}
