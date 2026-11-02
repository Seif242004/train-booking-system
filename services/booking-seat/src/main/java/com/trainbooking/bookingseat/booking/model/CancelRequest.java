package com.trainbooking.bookingseat.booking.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** An admin cancels a booking on the passenger's request (support) and says why; the reason is recorded (SRS FR-C7). */
public record CancelRequest(
        @NotBlank(message = "is required") @Size(max = 500, message = "must be at most 500 characters") String reason) {
}
