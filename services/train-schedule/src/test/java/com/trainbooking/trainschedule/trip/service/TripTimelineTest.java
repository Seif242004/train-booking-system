package com.trainbooking.trainschedule.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.trip.model.StopTimeRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TripTimelineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    private static StopTimeRequest stop(String arrival, String departure) {
        return new StopTimeRequest(arrival, departure);
    }

    private static List<String> fieldsOf(Throwable thrown) {
        return ((RequestValidationException) thrown).details().stream().map(ApiError.Detail::field).toList();
    }

    @Test
    void aDaytimeTrip_getsTheDepartureDateOnEveryTime() {
        List<TripTimeline.Times> times = TripTimeline.resolve(DAY, List.of(stop(null, "08:00"), stop("08:40", "08:45"), stop("10:45", null)));

        assertThat(times.get(0).arrival()).isNull();
        assertThat(times.get(0).departure()).isEqualTo(LocalDateTime.of(2026, 11, 2, 8, 0));
        assertThat(times.get(1).arrival()).isEqualTo(LocalDateTime.of(2026, 11, 2, 8, 40));
        assertThat(times.get(1).departure()).isEqualTo(LocalDateTime.of(2026, 11, 2, 8, 45));
        assertThat(times.get(2).arrival()).isEqualTo(LocalDateTime.of(2026, 11, 2, 10, 45));
        assertThat(times.get(2).departure()).isNull();
    }

    @Test
    void aTripPastMidnight_rollsTheLaterTimesIntoTheNextDay() {
        List<TripTimeline.Times> times = TripTimeline.resolve(DAY, List.of(stop(null, "23:30"), stop("00:10", "00:15"), stop("02:00", null)));

        assertThat(times.get(1).arrival()).isEqualTo(LocalDateTime.of(2026, 11, 3, 0, 10));
        assertThat(times.get(1).departure()).isEqualTo(LocalDateTime.of(2026, 11, 3, 0, 15));
        assertThat(times.get(2).arrival()).isEqualTo(LocalDateTime.of(2026, 11, 3, 2, 0));
    }

    @Test
    void aStopThatIsPassedThroughMidnightWhileStanding_rollsItsDeparture() {
        List<TripTimeline.Times> times = TripTimeline.resolve(DAY, List.of(stop(null, "22:00"), stop("23:55", "00:05"), stop("01:00", null)));

        assertThat(times.get(1).arrival()).isEqualTo(LocalDateTime.of(2026, 11, 2, 23, 55));
        assertThat(times.get(1).departure()).isEqualTo(LocalDateTime.of(2026, 11, 3, 0, 5));
        assertThat(times.get(2).arrival()).isEqualTo(LocalDateTime.of(2026, 11, 3, 1, 0));
    }

    @Test
    void theFirstStopMustHaveOnlyADeparture_theLastOnlyAnArrival_theOthersBoth() {
        assertThatThrownBy(() -> TripTimeline.resolve(DAY, List.of(stop("07:50", null), stop(null, "08:45"), stop("10:45", "11:00"))))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactlyInAnyOrder(
                        "stops[0].arrivalTime", "stops[0].departureTime", "stops[1].arrivalTime", "stops[2].departureTime"));
    }

    @Test
    void aTimeThatDoesNotAdvance_isRejected_namingTheStop() {
        // Arriving at the same minute the train left the previous stop is not a journey.
        assertThatThrownBy(() -> TripTimeline.resolve(DAY, List.of(stop(null, "08:00"), stop("08:00", "08:05"), stop("10:00", null))))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactly("stops[1].arrivalTime"));
    }

    @Test
    void aDepartureAtTheSameMinuteAsTheArrival_isAllowed() {
        List<TripTimeline.Times> times = TripTimeline.resolve(DAY, List.of(stop(null, "08:00"), stop("08:40", "08:40"), stop("10:00", null)));

        assertThat(times.get(1).departure()).isEqualTo(times.get(1).arrival());
    }

    @Test
    void aTripLongerThan24Hours_isRejected_becauseTheNextDayRuleCouldHideATypo() {
        // 08:00 then 07:59 is read as the next day: 23 h 59 min later, which is fine; 08:00 to 08:30 two stops later
        // after a rolled day would exceed 24 h.
        assertThatThrownBy(() -> TripTimeline.resolve(DAY, List.of(stop(null, "08:00"), stop("07:00", "07:05"), stop("08:30", null))))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactly("stops[2].arrivalTime"));
    }

    @Test
    void justUnder24Hours_isAllowed() {
        List<TripTimeline.Times> times = TripTimeline.resolve(DAY, List.of(stop(null, "08:00"), stop("07:59", null)));

        assertThat(times.get(1).arrival()).isEqualTo(LocalDateTime.of(2026, 11, 3, 7, 59));
    }
}
