package com.trainbooking.trainschedule.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import java.time.LocalDate;
import java.util.List;

/**
 * What the Gateway needs to draw the seat map of a trip (SRS 9.1, {@code GET /trips/{id}/layout}): the train, the stops
 * with their times, and every coach in train order with its seats and standing capacity. It holds no availability: that
 * comes from Booking and Seat, and the Gateway puts the two together.
 */
public record TripLayout(
        long tripId,
        TripStatus status,
        LocalDate departureDate,
        String trainNumber,
        String trainName,
        String trainType,
        String routeName,
        List<TripStop> stops,
        List<LayoutCoach> coaches) {

    public record LayoutCoach(
            long id,
            String code,
            @JsonProperty("class") CoachClass coachClass,
            int position,
            int standingCapacity,
            List<LayoutSeat> seats) {
    }

    public record LayoutSeat(long id, String seatNumber, int row, String column, @JsonProperty("class") CoachClass coachClass) {
    }
}
