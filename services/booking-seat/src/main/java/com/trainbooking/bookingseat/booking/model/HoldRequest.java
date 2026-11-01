package com.trainbooking.bookingseat.booking.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code POST /bookings} (SRS 9.1): the trip, the journey as stop indexes, and what to hold. The client never sends an
 * amount: it is worked out on the server. Either list may be missing but not both (checked by the service).
 */
public record HoldRequest(
        @NotNull(message = "is required")
        Long tripId,

        @NotNull(message = "is required") @Min(value = 0, message = "must be 0 or more")
        Integer fromStop,

        @NotNull(message = "is required") @Min(value = 0, message = "must be 0 or more")
        Integer toStop,

        @Size(max = 20, message = "must have at most 20 seats")
        List<@NotNull(message = "is required") Long> seatIds,

        @Size(max = 10, message = "must have at most 10 entries")
        List<@NotNull(message = "is required") @Valid StandingRequest> standing) {

    /** Places to stand in one coach. */
    public record StandingRequest(
            @NotNull(message = "is required") Long coachId,
            @NotNull(message = "is required") @Min(value = 1, message = "must be 1 or more") @Max(value = 20, message = "must be at most 20") Integer qty) {
    }

    public List<Long> seats() {
        return seatIds == null ? List.of() : seatIds;
    }

    public List<StandingRequest> standingPlaces() {
        return standing == null ? List.of() : standing;
    }
}
