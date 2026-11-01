package com.trainbooking.payment.refund.model;

/** The booking has no succeeded payment, so there is nothing to refund. Answered as 404. */
public class NoPaymentToRefundException extends RuntimeException {

    public NoPaymentToRefundException(long bookingId) {
        super("Booking " + bookingId + " has no payment that was taken, so there is nothing to refund.");
    }
}
