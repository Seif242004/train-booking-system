package com.trainbooking.bookingseat.booking.model;

/** The refund of a cancelled booking, mirrored from Payment (CLAUDE.md, "Refund state"). */
public enum RefundStatus {
    NONE,
    REFUND_PENDING,
    REFUNDED,
    REFUND_FAILED
}
