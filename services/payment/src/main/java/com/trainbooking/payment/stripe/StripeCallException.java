package com.trainbooking.payment.stripe;

/**
 * Stripe could not give an answer (no connection, a timeout, rate limiting, a 5xx), or refused the request in a way that this service
 * cannot fix by itself. The state in the database is left as it was, so the caller can try again.
 */
public class StripeCallException extends RuntimeException {

    public StripeCallException(String message, Throwable cause) {
        super(message, cause);
    }

    public StripeCallException(String message) {
        super(message);
    }
}
