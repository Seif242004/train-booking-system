package com.trainbooking.bookingseat.outbox.model;

import java.time.OffsetDateTime;

/** One job for Ticket or Notification that has not been done yet. */
public record OutboxMessage(long id, Kind kind, long bookingId, String payload, int attempts, OffsetDateTime nextAttemptAt) {

    public enum Kind {
        /** Make the ticket of a confirmed booking and email it (also used to resend it). */
        TICKET_EMAIL,
        /** The booking was cancelled: invalidate the QR token of the ticket. */
        TICKET_INVALIDATE,
        /** Tell the passenger the booking is cancelled. */
        CANCELLATION_EMAIL,
        /** Tell the passenger the refund is done. */
        REFUND_EMAIL
    }
}
