package com.trainbooking.trainschedule.trip.model;

import jakarta.validation.constraints.Pattern;

/**
 * The times at one stop, as {@code HH:mm} (24-hour, local time). The first stop has only a departure time, the last stop
 * only an arrival time, every other stop both; the service checks that. A time that is earlier than the one before it
 * means the next day, so a trip can run past midnight.
 */
public record StopTimeRequest(
        // @Pattern: the text must match the regular expression, 00:00 to 23:59. Null is allowed here (the first stop has no arrival, the
        // last no
        // departure); the service decides which stop needs which time (TripTimeline).
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "must be a time as HH:mm")
        String arrivalTime,

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "must be a time as HH:mm")
        String departureTime) {
}
