package com.trainbooking.trainschedule.seat.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trainbooking.trainschedule.coach.model.CoachClass;

/**
 * A seat as stored and returned by the API (SRS section 8: id, coach_id, seat_number, row, column, class).
 * The database columns are {@code seat_row} and {@code seat_column} because ROW and COLUMN are Oracle reserved words;
 * the JSON names stay {@code row} and {@code column}. {@code class} is always the class of the seat's coach.
 */
public record Seat(
        long id,
        long coachId,
        String seatNumber,
        int row,
        String column,
        @JsonProperty("class") CoachClass coachClass) {
}
