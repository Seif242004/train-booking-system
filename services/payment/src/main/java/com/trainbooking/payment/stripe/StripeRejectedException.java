package com.trainbooking.payment.stripe;

/**
 * Stripe understood the request and refused it for good (a 4xx other than rate limiting: an invalid amount, a charge that was already
 * refunded...). Unlike {@link StripeCallException} ("no answer"), repeating the same request cannot change the answer.
 */
public class StripeRejectedException extends RuntimeException {

    public StripeRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
