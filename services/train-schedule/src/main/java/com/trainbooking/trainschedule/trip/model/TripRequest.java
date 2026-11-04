package com.trainbooking.trainschedule.trip.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Body of create and update (FR-A8). {@code stops} has one entry per stop of the route, in the route's order. The status
 * is not part of the request: a trip starts ACTIVE and is only cancelled by the whole-trip cancel (slice 12).
 */
public record TripRequest(
        // Rules: @NotNull = present; @Size(min, max) = number of list entries; @Valid on the list element makes Bean Validation also
        // check each StopTimeRequest.
        // The service checks the rest: train and route exist, the list matches the route's stops, times make sense, a trip lasts at most
        // 24 hours.
        @NotNull(message = "is required")
        Long trainId,

        @NotNull(message = "is required")
        Long routeId,

        @NotNull(message = "is required")
        LocalDate departureDate,

        @NotNull(message = "is required")
        @Size(min = 2, max = 50, message = "must have one entry per stop of the route")
        List<@NotNull(message = "is required") @Valid StopTimeRequest> stops) {
}
