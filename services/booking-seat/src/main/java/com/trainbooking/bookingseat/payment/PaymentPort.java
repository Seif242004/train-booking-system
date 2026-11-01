package com.trainbooking.bookingseat.payment;

import java.math.BigDecimal;

/**
 * What Booking and Seat needs from the Payment service. It is an interface so that the booking logic (the sweeper above all)
 * can be tested without Stripe, and so that Booking never depends on how Payment talks to Stripe.
 */
public interface PaymentPort {

    /** What happened to the pending payment of a booking when it was asked to cancel (SRS 4.2, FR-P4). */
    enum CancelOutcome {
        /** There was no payment for the booking: nothing to cancel. */
        NONE,
        /** The pending payment is cancelled (or was): nobody can pay it any more, so the seats may be released. */
        CANCELLED,
        /** Stripe refuses to cancel because the payment already succeeded: the payment wins and the booking is confirmed. */
        SUCCEEDED,
        /** The payment is still being processed (cards only): the seats stay held and the sweeper asks again. */
        PROCESSING
    }

    /**
     * Cancels the pending payment of a booking. This is a call to another service and then to Stripe, so it must never be made
     * inside an open database transaction (CLAUDE.md).
     *
     * @throws PaymentUnavailableException if the answer is not known (Payment or Stripe is down): the caller keeps the seats
     *                                     held and tries again later
     */
    CancelOutcome cancelPending(long bookingId);

    /**
     * Cancels the open payment of a booking because the booking changes (a seat was picked or released after the user had gone to the
     * payment page), so that the amount of the payment never differs from the amount of the booking. Unlike {@link #cancelPending}, a new
     * payment may be created afterwards. The outcomes are the same: NONE or CANCELLED when it is done, SUCCEEDED or PROCESSING when the
     * booking must not change any more. Like cancelPending, never inside a database transaction.
     */
    CancelOutcome invalidate(long bookingId);

    /** What the payment page needs to show Stripe's embedded form. */
    record PaymentSession(String clientSecret, String publishableKey, BigDecimal amount, String currency) {
    }

    /**
     * Creates (or returns) the payment of a booking for the amount Booking worked out. The caller has already set payment_started on the
     * booking, so from now on every change of the hold goes through {@link #invalidate} and {@link #cancelPending}.
     *
     * @throws PaymentRejectedException     Payment refused (409): the hold ended, already paid, a payment is being created...
     * @throws PaymentUnavailableException  Payment or Stripe could not be reached
     */
    PaymentSession createPayment(long bookingId, BigDecimal amount, String currency);
}
