package com.trainbooking.payment.payment.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The payment of one booking (one row per booking).
 *
 * @param intentId the Stripe payment intent, null while {@code CREATING} and for a tombstone
 * @param revision 1, then 2... each time the payment was invalidated and made again; it is part of the Stripe idempotency key, so a
 *                 new payment is a new intent while a retry of the same payment returns the same intent
 * @param ended    true once the hold of the booking has ended: the payment can never be created again (a row with
 *                 {@code CANCELLED} and no intent is a tombstone that refuses a creation that was already on its way)
 */
public record Payment(
        long id,
        long bookingId,
        String intentId,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String lastError,
        int revision,
        boolean ended,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
