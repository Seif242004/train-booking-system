package com.trainbooking.bookingseat.payment;

/** The Payment service or Stripe could not be reached, so what happened to a payment is not known. */
// 503 payment_unavailable / stripe_not_configured.
public class PaymentUnavailableException extends RuntimeException {

    private final String code;

    public PaymentUnavailableException(String message, Throwable cause) {
        this("payment_unavailable", message, cause);
    }

    /** @param code {@code payment_unavailable}, or {@code stripe_not_configured} when the deployment has no Stripe key */
    public PaymentUnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
