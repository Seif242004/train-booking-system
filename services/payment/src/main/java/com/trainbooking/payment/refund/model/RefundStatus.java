package com.trainbooking.payment.refund.model;

// Payment's own refund states (CHECK ck_refund_status). Refund.bookingStatus() translates them to the names Booking uses.
// PENDING: not final (Stripe not reached yet, or Stripe still working). SUCCEEDED and FAILED are final.
public enum RefundStatus {
    PENDING, SUCCEEDED, FAILED
}
