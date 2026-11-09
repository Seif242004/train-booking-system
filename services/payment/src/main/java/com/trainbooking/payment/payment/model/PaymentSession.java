package com.trainbooking.payment.payment.model;

import java.math.BigDecimal;

/**
 * What the payment page needs to show Stripe's form: the client secret of the intent and the publishable key (public by design).
 * The secret key and the webhook secret never leave this service.
 */
// The JSON answer of POST /internal/payments. Booking passes clientSecret and publishableKey on to the browser; the browser's Stripe
// Payment Element uses them to send the card data straight to Stripe. Nothing secret about our account is in here.
public record PaymentSession(long paymentId, String clientSecret, String publishableKey, BigDecimal amount, String currency) {
}
