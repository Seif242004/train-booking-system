package com.trainbooking.bookingseat.booking.model;

/**
 * The user (in another tab, say) opened the payment page between the moment a change of the hold looked at the booking and the moment
 * it locked it. The open payment has not been cancelled yet, so the change is rolled back and tried again, this time cancelling the
 * payment first. It never reaches the client unless that keeps happening.
 */
public class PaymentStartedMeanwhileException extends RuntimeException {

    public PaymentStartedMeanwhileException() {
        super("A payment was started while the hold was being changed.");
    }
}
