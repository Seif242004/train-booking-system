package com.trainbooking.payment.refund.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Booking and Seat asks for the full refund of a booking's payment (a cancellation, or money that arrived for a hold that was gone). */
public record RefundRequest(
        @NotNull(message = "is required") @Positive(message = "must be above 0") Long bookingId,
        @Size(max = 500, message = "must be at most 500 characters") String reason) {
}
