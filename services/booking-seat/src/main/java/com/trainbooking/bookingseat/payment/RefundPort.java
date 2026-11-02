package com.trainbooking.bookingseat.payment;

/**
 * Asking Payment for a refund. It is deliberately a different interface from {@link PaymentPort}: the expiry of a hold only ever talks to
 * {@code PaymentPort}, which can cancel but not refund, so a timeout can never refund (CLAUDE.md, FR-P4). Only a cancellation of a
 * confirmed booking uses this one.
 */
public interface RefundPort {

    enum Result {
        /** Payment has the refund (new, or the same one asked for before): it is refunded to Stripe in the background. */
        REQUESTED,
        /** The booking has no payment that was taken, so there is nothing to refund. */
        NO_PAYMENT
    }

    /**
     * Asks for the full refund of a booking's payment. Idempotent: asking again returns the same refund.
     *
     * @throws PaymentUnavailableException if the answer is not known: the caller tries again later
     */
    Result requestRefund(long bookingId, String reason);
}
