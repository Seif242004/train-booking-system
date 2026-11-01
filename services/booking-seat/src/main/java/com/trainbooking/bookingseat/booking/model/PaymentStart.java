package com.trainbooking.bookingseat.booking.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The answer of "Continue to payment" (SRS 7.1): what the payment page needs to show Stripe's embedded form, and the time left of the
 * hold, so the countdown goes on from where it was. The amount is the one the server worked out.
 */
public record PaymentStart(String clientSecret, String publishableKey, BigDecimal amount, String currency, OffsetDateTime expiresAt, OffsetDateTime serverTime) {
}
