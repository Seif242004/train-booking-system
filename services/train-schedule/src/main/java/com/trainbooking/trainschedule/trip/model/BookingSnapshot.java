package com.trainbooking.trainschedule.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The immutable copy of a trip that is pushed to Booking and Seat (CLAUDE.md, "Data copy"): the stops with their times,
 * the coaches with their standing capacity, the seats and the fares. It is the body of
 * {@code PUT /internal/trips/{id}/snapshot} and has the same shape as the snapshot that Booking and Seat receives. Times
 * carry their offset, so they mean the same moment on both sides.
 */
public record BookingSnapshot(
        LocalDate departureDate,
        Train train,
        String routeName,
        List<Stop> stops,
        List<Coach> coaches,
        List<Seat> seats,
        List<Fare> fares) {

    public record Train(String number, String name, String type) {
    }

    public record Stop(int stopIndex, long stationId, String stationCode, String stationName, OffsetDateTime arrivalTime,
                       OffsetDateTime departureTime) {
    }

    public record Coach(long id, String code, @JsonProperty("class") CoachClass coachClass, int position, int standingCapacity) {
    }

    public record Seat(long id, long coachId, String seatNumber, int row, String column, @JsonProperty("class") CoachClass coachClass) {
    }

    public record Fare(int segmentIndex, @JsonProperty("class") CoachClass coachClass, BigDecimal amount, String currency) {
    }
}
