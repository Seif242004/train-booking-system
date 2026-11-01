package com.trainbooking.bookingseat.payment;

/** The Payment service or Stripe could not be reached, so what happened to a payment is not known. */
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
