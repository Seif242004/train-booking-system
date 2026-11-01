package com.trainbooking.bookingseat.booking.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Body of {@code POST /bookings/{id}/release}: deselecting one seat, or giving back some standing places. It releases at once.
 * Exactly one of {@code seatId} and {@code coachId} must be given; {@code qty} is for standing places only (1 if missing).
 * When the last item of the hold is released, the hold ends and the booking is ABANDONED.
 */
public record ReleaseRequest(
        Long seatId,
        Long coachId,

        @Min(value = 1, message = "must be 1 or more") @Max(value = 20, message = "must be at most 20")
        Integer qty) {

    public boolean isSeat() {
        return seatId != null;
    }

    public int places() {
        return qty == null ? 1 : qty;
    }
}
