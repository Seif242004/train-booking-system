package com.trainbooking.payment.payment.model;

import java.math.BigDecimal;

/**
 * What the payment page needs to show Stripe's form: the client secret of the intent and the publishable key (public by design).
 * The secret key and the webhook secret never leave this service.
 */
public record PaymentSession(long paymentId, String clientSecret, String publishableKey, BigDecimal amount, String currency) {
}
