package com.trainbooking.payment.payment.model;

/** The request clashes with the state of the payment; answered as 409 with the given code. */
public class PaymentConflictException extends RuntimeException {

    private final String code;

    public PaymentConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
