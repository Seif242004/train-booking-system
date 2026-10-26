package com.trainbooking.trainschedule.trip.service;

import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.trip.model.StopTimeRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@code HH:mm} times of a trip request into full date-times. The admin enters a departure date and clock times;
 * a clock time that is earlier than the time before it means the next day, so a trip can run past midnight.
 * Rules, all reported together:
 * <ul>
 *   <li>the first stop has only a departure time, the last only an arrival time, every other stop both;</li>
 *   <li>each arrival is after the previous stop's departure, and each departure is not before the stop's arrival;</li>
 *   <li>the whole trip lasts at most 24 hours (a larger gap is almost certainly a typo that the "next day" rule hid).</li>
 * </ul>
 */
final class TripTimeline {

    static final Duration MAX_TRIP_DURATION = Duration.ofHours(24);

    /** The resolved times of one stop. {@code arrival} is null at the first stop, {@code departure} at the last. */
    record Times(LocalDateTime arrival, LocalDateTime departure) {
    }

    private TripTimeline() {
    }

    static List<Times> resolve(LocalDate departureDate, List<StopTimeRequest> stops) {
        List<ApiError.Detail> problems = new ArrayList<>();
        checkShape(stops, problems);
        if (!problems.isEmpty()) {
            throw new RequestValidationException(problems);
        }

        List<Times> resolved = new ArrayList<>();
        LocalDateTime previous = null;
        LocalDateTime firstDeparture = null;
        LocalDate day = departureDate;

        for (int i = 0; i < stops.size(); i++) {
            LocalDateTime arrival = null;
            LocalDateTime departure = null;

            if (stops.get(i).arrivalTime() != null) {
                arrival = LocalDateTime.of(day, LocalTime.parse(stops.get(i).arrivalTime()));
                if (previous != null && arrival.isBefore(previous)) {
                    arrival = arrival.plusDays(1); // the clock went back, so this is after midnight
                }
                if (previous != null && !arrival.isAfter(previous)) {
                    problems.add(new ApiError.Detail("stops[" + i + "].arrivalTime", "must be after the previous stop's departure"));
                }
                previous = arrival;
                day = arrival.toLocalDate();
            }
            if (stops.get(i).departureTime() != null) {
                departure = LocalDateTime.of(day, LocalTime.parse(stops.get(i).departureTime()));
                if (previous != null && departure.isBefore(previous)) {
                    departure = departure.plusDays(1);
                }
                if (firstDeparture == null) {
                    firstDeparture = departure;
                }
                previous = departure;
                day = departure.toLocalDate();
            }
            resolved.add(new Times(arrival, departure));
        }

        LocalDateTime last = resolved.get(resolved.size() - 1).arrival();
        if (firstDeparture != null && last != null && Duration.between(firstDeparture, last).compareTo(MAX_TRIP_DURATION) > 0) {
            problems.add(new ApiError.Detail("stops[" + (stops.size() - 1) + "].arrivalTime",
                    "the trip would last more than 24 hours; check the times"));
        }
        if (!problems.isEmpty()) {
            throw new RequestValidationException(problems);
        }
        return resolved;
    }

    /** Which times each stop must and must not have. */
    private static void checkShape(List<StopTimeRequest> stops, List<ApiError.Detail> problems) {
        int last = stops.size() - 1;
        for (int i = 0; i <= last; i++) {
            StopTimeRequest stop = stops.get(i);
            if (i == 0) {
                if (stop.arrivalTime() != null) {
                    problems.add(new ApiError.Detail("stops[0].arrivalTime", "must be empty at the first stop"));
                }
                if (stop.departureTime() == null) {
                    problems.add(new ApiError.Detail("stops[0].departureTime", "is required"));
                }
            } else if (i == last) {
                if (stop.arrivalTime() == null) {
                    problems.add(new ApiError.Detail("stops[" + i + "].arrivalTime", "is required"));
                }
                if (stop.departureTime() != null) {
                    problems.add(new ApiError.Detail("stops[" + i + "].departureTime", "must be empty at the last stop"));
                }
            } else {
                if (stop.arrivalTime() == null) {
                    problems.add(new ApiError.Detail("stops[" + i + "].arrivalTime", "is required"));
                }
                if (stop.departureTime() == null) {
                    problems.add(new ApiError.Detail("stops[" + i + "].departureTime", "is required"));
                }
            }
        }
    }
}
