package com.trainbooking.bookingseat.booking.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /bookings/pick} (Flow 3): one pick on the seat map, a seat or some standing places. The first pick of a user
 * on a trip starts the hold (and its 5 minutes); every further pick joins the same booking. Exactly one of {@code seatId} and
 * {@code coachId} must be given; {@code qty} is for standing places only (1 if missing). The client never sends an amount: it is
 * worked out on the server.
 */
public record PickRequest(
        @NotNull(message = "is required")
        Long tripId,

        @NotNull(message = "is required") @Min(value = 0, message = "must be 0 or more")
        Integer fromStop,

        @NotNull(message = "is required") @Min(value = 0, message = "must be 0 or more")
        Integer toStop,

        /** A seat to pick. */
        Long seatId,

        /** The coach to take standing places in. */
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
