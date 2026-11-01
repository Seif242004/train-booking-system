package com.trainbooking.payment.refund.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The full refund of the payment of one booking (there are no partial refunds and at most one refund per booking). The id of the row
 * is the idempotency key of the Stripe call, so no retry can refund twice.
 *
 * @param stripeRefundId null until Stripe has accepted the request
 * @param nextAttemptAt  when Stripe could not be reached: the next try (only used while there is no Stripe refund yet)
 */
public record Refund(
        long id,
        long bookingId,
        long paymentId,
        String stripeRefundId,
        BigDecimal amount,
        RefundStatus status,
        String reason,
        String failureReason,
        int attempts,
        OffsetDateTime nextAttemptAt) {

    /** The names Booking and Seat uses for the refund status of a booking (CLAUDE.md: REFUND_PENDING, REFUNDED, REFUND_FAILED). */
    public String bookingStatus() {
        return switch (status) {
            case PENDING -> "REFUND_PENDING";
            case SUCCEEDED -> "REFUNDED";
            case FAILED -> "REFUND_FAILED";
        };
    }
}
