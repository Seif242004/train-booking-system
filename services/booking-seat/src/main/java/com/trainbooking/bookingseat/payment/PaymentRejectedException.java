package com.trainbooking.bookingseat.payment;

/** The Payment service understood the request and refused it (HTTP 409), with a code the client can act on. */
public class PaymentRejectedException extends RuntimeException {

    private final String code;

    public PaymentRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
