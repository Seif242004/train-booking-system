package com.trainbooking.payment.stripe;

/** There is no Stripe secret key (or webhook secret) in the settings, so nothing that needs Stripe can be done. Answered as 503. */
public class StripeNotConfiguredException extends RuntimeException {

    public StripeNotConfiguredException(String setting) {
        super(setting + " is not set, so Stripe cannot be used.");
    }
}
