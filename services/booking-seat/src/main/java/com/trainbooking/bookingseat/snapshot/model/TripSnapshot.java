package com.trainbooking.bookingseat.snapshot.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trainbooking.bookingseat.common.CoachClass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The immutable copy of a trip that Train and Schedule pushes to Booking and Seat (CLAUDE.md, "Data copy"; SRS section 8,
 * trip_snapshot): the stops with their times, the coaches with their standing capacity, the seats and the fares. The id
 * of the trip is the one in the URL. Field rules that need more than one field (stops numbered in order, every seat in a
 * coach of the snapshot, a fare for every segment and class) are checked by {@link SnapshotValidator}.
 */
public record TripSnapshot(
        @NotNull(message = "is required")
        LocalDate departureDate,

        @NotNull(message = "is required") @Valid
        Train train,

        @NotBlank(message = "is required") @Size(max = 100, message = "must be at most 100 characters")
        String routeName,

        @NotNull(message = "is required") @Size(min = 2, max = 50, message = "must have 2 to 50 stops")
        List<@NotNull(message = "is required") @Valid Stop> stops,

        @NotNull(message = "is required") @Size(min = 1, max = 100, message = "must have 1 to 100 coaches")
        List<@NotNull(message = "is required") @Valid Coach> coaches,

        @NotNull(message = "is required") @Size(max = 10000, message = "must have at most 10000 seats")
        List<@NotNull(message = "is required") @Valid Seat> seats,

        @NotNull(message = "is required") @Size(max = 1000, message = "must have at most 1000 fares")
        List<@NotNull(message = "is required") @Valid Fare> fares) {

    public record Train(
            @NotBlank(message = "is required") @Size(max = 20, message = "must be at most 20 characters") String number,
            @NotBlank(message = "is required") @Size(max = 100, message = "must be at most 100 characters") String name,
            @NotBlank(message = "is required") @Size(max = 50, message = "must be at most 50 characters") String type) {
    }

    /** A stop of the trip. The first stop has no arrival time and the last has no departure time. */
    public record Stop(
            @NotNull(message = "is required") @Min(value = 0, message = "must be 0 or more") Integer stopIndex,
            @NotNull(message = "is required") Long stationId,
            @NotBlank(message = "is required") @Size(max = 10, message = "must be at most 10 characters") String stationCode,
            @NotBlank(message = "is required") @Size(max = 100, message = "must be at most 100 characters") String stationName,
            OffsetDateTime arrivalTime,
            OffsetDateTime departureTime) {
    }

    /** A coach and its standing capacity (0 for none). The copy of the capacity lives in standing_usage. */
    public record Coach(
            @NotNull(message = "is required") Long id,
            @NotBlank(message = "is required") @Size(max = 10, message = "must be at most 10 characters") String code,
            @JsonProperty("class") @NotNull(message = "is required") CoachClass coachClass,
            @NotNull(message = "is required") @Min(value = 1, message = "must be 1 or more") Integer position,
            @NotNull(message = "is required") @Min(value = 0, message = "must be 0 or more") Integer standingCapacity) {
    }

    /** A seat. {@code class} must be the class of its coach. The JSON names are row and column, as in Train and Schedule. */
    public record Seat(
            @NotNull(message = "is required") Long id,
            @NotNull(message = "is required") Long coachId,
            @NotBlank(message = "is required") @Size(max = 10, message = "must be at most 10 characters") String seatNumber,
            @NotNull(message = "is required") @Min(value = 1, message = "must be 1 or more") Integer row,
            @NotBlank(message = "is required") @Size(max = 3, message = "must be at most 3 characters") String column,
            @JsonProperty("class") @NotNull(message = "is required") CoachClass coachClass) {
    }

    /** The fare of one segment for one class of coach. */
    public record Fare(
            @NotNull(message = "is required") @Min(value = 0, message = "must be 0 or more") Integer segmentIndex,
            @JsonProperty("class") @NotNull(message = "is required") CoachClass coachClass,
            @NotNull(message = "is required") BigDecimal amount,
            @NotBlank(message = "is required") String currency) {
    }
}
