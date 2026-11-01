package com.trainbooking.bookingseat.snapshot.service;

import static com.trainbooking.bookingseat.testsupport.Snapshots.replace;
import static com.trainbooking.bookingseat.testsupport.Snapshots.valid;
import static com.trainbooking.bookingseat.testsupport.Snapshots.without;
import static com.trainbooking.bookingseat.testsupport.Snapshots.withCoaches;
import static com.trainbooking.bookingseat.testsupport.Snapshots.withFares;
import static com.trainbooking.bookingseat.testsupport.Snapshots.withSeats;
import static com.trainbooking.bookingseat.testsupport.Snapshots.withStops;
import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.CoachClass;
import com.trainbooking.bookingseat.snapshot.model.TripSnapshot;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rules that involve more than one field of a snapshot: the parts must fit together, or nothing is stored. */
class SnapshotValidatorTest {

    private static List<String> problems(TripSnapshot snapshot) {
        return SnapshotValidator.check(snapshot).stream().map(d -> d.field() + ": " + d.message()).toList();
    }

    private static List<String> fields(TripSnapshot snapshot) {
        return SnapshotValidator.check(snapshot).stream().map(ApiError.Detail::field).toList();
    }

    @Test
    void aCompleteSnapshot_hasNoProblems() {
        assertThat(SnapshotValidator.check(valid())).isEmpty();
    }

    @Test
    void stopsMustBeNumberedFromZeroInOrder() {
        TripSnapshot skipped = withStops(valid(), replace(valid().stops(), 2, s -> new TripSnapshot.Stop(5, s.stationId(), s.stationCode(), s.stationName(), s.arrivalTime(), s.departureTime())));

        assertThat(fields(skipped)).containsExactly("stops[2].stopIndex");
    }

    @Test
    void aStationMayAppearOnlyOnceInTheStops() {
        TripSnapshot repeated = withStops(valid(), replace(valid().stops(), 3, s -> new TripSnapshot.Stop(3, 100L, "CAI", "Cairo", s.arrivalTime(), null)));

        assertThat(fields(repeated)).containsExactly("stops[3].stationId");
    }

    @Test
    void theFirstStopHasNoArrival_andTheLastStopHasNoDeparture() {
        TripSnapshot first = withStops(valid(), replace(valid().stops(), 0, s -> new TripSnapshot.Stop(0, s.stationId(), s.stationCode(), s.stationName(), s.departureTime(), s.departureTime())));
        TripSnapshot last = withStops(valid(), replace(valid().stops(), 3, s -> new TripSnapshot.Stop(3, s.stationId(), s.stationCode(), s.stationName(), s.arrivalTime(), s.arrivalTime())));

        assertThat(fields(first)).containsExactly("stops[0].arrivalTime");
        assertThat(fields(last)).containsExactly("stops[3].departureTime");
    }

    @Test
    void everyOtherStopHasBothTimes() {
        TripSnapshot missing = withStops(valid(), replace(valid().stops(), 1, s -> new TripSnapshot.Stop(1, s.stationId(), s.stationCode(), s.stationName(), null, s.departureTime())));

        assertThat(fields(missing)).containsExactly("stops[1].arrivalTime");
    }

    @Test
    void coachIdsCodesAndPositions_mustBeUnique() {
        TripSnapshot clash = withCoaches(valid(), replace(valid().coaches(), 2, c -> new TripSnapshot.Coach(10L, "A", c.coachClass(), 1, 5)));

        // Coach 30 is replaced here, so its seat is also reported; the coach problems are the ones this test is about.
        assertThat(fields(clash).stream().filter(f -> f.startsWith("coaches[")).toList())
                .containsExactlyInAnyOrder("coaches[2].id", "coaches[2].code", "coaches[2].position");
    }

    @Test
    void aSeatMustBeInACoachOfTheSnapshot() {
        TripSnapshot orphan = withSeats(valid(), replace(valid().seats(), 0, s -> new TripSnapshot.Seat(1L, 99L, "A1", 1, "A", CoachClass.FIRST)));

        assertThat(problems(orphan)).containsExactly("seats[0].coachId: coach 99 is not in the snapshot");
    }

    @Test
    void aSeatHasTheClassOfItsCoach() {
        TripSnapshot wrongClass = withSeats(valid(), replace(valid().seats(), 0, s -> new TripSnapshot.Seat(1L, 10L, "A1", 1, "A", CoachClass.THIRD)));

        assertThat(fields(wrongClass)).containsExactly("seats[0].class");
    }

    @Test
    void seatIdsAndSeatNumbersWithinACoach_mustBeUnique() {
        TripSnapshot sameId = withSeats(valid(), replace(valid().seats(), 2, s -> new TripSnapshot.Seat(2L, 20L, "B2", 1, "B", CoachClass.SECOND)));
        TripSnapshot sameNumber = withSeats(valid(), replace(valid().seats(), 2, s -> new TripSnapshot.Seat(3L, 20L, "B1", 1, "B", CoachClass.SECOND)));

        assertThat(fields(sameId)).containsExactly("seats[2].id");
        assertThat(fields(sameNumber)).containsExactly("seats[2].seatNumber");
    }

    @Test
    void aFareMustBeForASegmentOfTheTrip() {
        List<TripSnapshot.Fare> fares = new java.util.ArrayList<>(valid().fares());
        fares.add(new TripSnapshot.Fare(3, CoachClass.FIRST, new BigDecimal("10.00"), "EGP")); // a 4-stop trip has segments 0 to 2

        assertThat(fields(withFares(valid(), fares))).containsExactly("fares[9].segmentIndex");
    }

    @Test
    void everySegmentNeedsAFareForEveryClassOfCoach() {
        TripSnapshot missing = withFares(valid(), without(valid().fares(), 4)); // segment 1, SECOND

        assertThat(problems(missing)).containsExactly("fares: segment 1 has no SECOND fare");
    }

    @Test
    void aClassWithoutAnyCoach_needsNoFares() {
        TripSnapshot noThirdClass = withSeats(withCoaches(valid(), without(valid().coaches(), 2)), without(valid().seats(), 3));

        assertThat(SnapshotValidator.check(noThirdClass)).isEmpty(); // the THIRD fares are simply not used
    }

    @Test
    void aFareIsListedOnlyOncePerSegmentAndClass() {
        List<TripSnapshot.Fare> fares = new java.util.ArrayList<>(valid().fares());
        fares.add(new TripSnapshot.Fare(0, CoachClass.FIRST, new BigDecimal("99.00"), "EGP"));

        assertThat(fields(withFares(valid(), fares))).containsExactly("fares[9]");
    }

    @Test
    void aFareIsAPositiveAmountOfAtMostTwoDecimals_inEgp() {
        TripSnapshot zero = withFares(valid(), replace(valid().fares(), 0, f -> new TripSnapshot.Fare(0, f.coachClass(), BigDecimal.ZERO, "EGP")));
        TripSnapshot threeDecimals = withFares(valid(), replace(valid().fares(), 0, f -> new TripSnapshot.Fare(0, f.coachClass(), new BigDecimal("80.005"), "EGP")));
        TripSnapshot dollars = withFares(valid(), replace(valid().fares(), 0, f -> new TripSnapshot.Fare(0, f.coachClass(), f.amount(), "USD")));

        assertThat(fields(zero)).containsExactly("fares[0].amount");
        assertThat(fields(threeDecimals)).containsExactly("fares[0].amount");
        assertThat(fields(dollars)).containsExactly("fares[0].currency");
    }
}
