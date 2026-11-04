package com.trainbooking.bookingseat.booking.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An admin confirms a hold without Stripe (docs/design/COUNTER-BOOKING.md). {@code method} is COUNTER or COMPLIMENTARY; {@code note} is the optional
 * receipt of a counter payment; {@code reason} is required for a complimentary booking. The rules that depend on the method are checked by the service.
 */
public record IssueRequest(
        @NotNull(message = "is required") PaymentMethod method,
        @Size(max = 200, message = "must be at most 200 characters") String note,
        @Size(max = 500, message = "must be at most 500 characters") String reason,
        @NotNull(message = "is required") @Valid Passenger passenger) {

    /** ACCOUNT: an existing passenger found by exact email. WALK_IN: a passenger without an account. */
    public record Passenger(
            @NotNull(message = "is required") Mode mode,
            @Size(max = 254, message = "must be at most 254 characters") String email,
            @Size(max = 200, message = "must be at most 200 characters") String name,
            @Size(max = 40, message = "must be at most 40 characters") String phone) {

        public enum Mode { ACCOUNT, WALK_IN }
    }
}
