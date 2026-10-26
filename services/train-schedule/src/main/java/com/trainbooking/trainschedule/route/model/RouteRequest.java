package com.trainbooking.trainschedule.route.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of create and update (FR-A8). {@code stops} are station ids in travel order: the first is stop 0. A route needs
 * at least two stops, and may visit a station only once.
 */
public record RouteRequest(
        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String name,

        @NotNull(message = "is required")
        @Size(min = 2, max = 50, message = "must list 2 to 50 stations")
        List<@NotNull(message = "is required") @Positive(message = "must be a station id") Long> stops) {
}
