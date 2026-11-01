package com.trainbooking.bookingseat.payment;

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
}
