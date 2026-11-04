package com.trainbooking.bookingseat.booking.model;

/** The refund of a cancelled booking, mirrored from Payment (CLAUDE.md, "Refund state"). */
public enum RefundStatus {
    NONE,
    REFUND_PENDING,
    REFUNDED,
    REFUND_FAILED,
    /** Complimentary booking cancelled: nothing was charged. */
    NOT_APPLICABLE,
    /** Counter booking cancelled: the money is returned at the counter. */
    MANUAL_REFUND_DUE,
    /** An admin returned the money at the counter. */
    REFUNDED_AT_COUNTER
}
