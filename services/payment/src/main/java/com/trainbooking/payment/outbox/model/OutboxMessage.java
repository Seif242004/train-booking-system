package com.trainbooking.payment.outbox.model;

import java.time.OffsetDateTime;

/** One piece of news for Booking and Seat that has not been delivered yet (or was, and is kept as a record). */
// One row of table outbox. The payload is JSON text. Delivered in order per booking, see OutboxRepository.findDue.
public record OutboxMessage(long id, Kind kind, long bookingId, String payload, int attempts, OffsetDateTime nextAttemptAt) {

    public enum Kind {
        /** A payment succeeded: Booking confirms the booking (or says the money has to be refunded). */
        PAYMENT_RESULT,
        /** A refund changed: Booking mirrors it in the refund status of the booking. */
        REFUND_STATUS
    }
}
