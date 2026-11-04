package com.trainbooking.bookingseat.board.model;

import java.time.OffsetDateTime;

/** One row of the live trips board. No per-seat data: the list stays small. */
public record BoardTrip(
        long tripId,
        String trainNumber,
        String trainName,
        String routeName,
        String origin,
        String destination,
        OffsetDateTime departureTime,
        OffsetDateTime arrivalTime,
        String tripStatus,
        TripState state,
        boolean full,
        SeatCounts first,
        SeatCounts second,
        SeatCounts third,
        SeatCounts standing,
        SeatCounts totals,
        /** Only for DEPARTED and ARRIVED trips: when it left, and the final numbers (holds cannot start after departure). */
        OffsetDateTime departedAt,
        Integer totalSeats,
        Integer confirmedAtDeparture) {
}
