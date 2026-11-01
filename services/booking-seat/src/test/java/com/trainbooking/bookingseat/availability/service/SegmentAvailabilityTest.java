package com.trainbooking.bookingseat.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.bookingseat.availability.model.CoachStandingLeft;
import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.availability.model.JourneyAvailability;
import com.trainbooking.bookingseat.availability.model.ReservationInfo;
import com.trainbooking.bookingseat.availability.model.SeatInfo;
import com.trainbooking.bookingseat.availability.model.SeatState;
import com.trainbooking.bookingseat.availability.model.StandingInfo;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.CoachClass;
import com.trainbooking.bookingseat.common.RequestValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The full-journey rule (SRS 4.1). A trip with 4 stops (0 to 3) has segments 0, 1 and 2. A journey from stop i to stop j
 * uses segments i to j-1, and a seat is available only if it is free on every one of them. Standing places left in a
 * coach are the minimum of (capacity - used) over those segments.
 */
class SegmentAvailabilityTest {

    private static final int STOPS = 4;

    private static final SeatInfo FIRST_1 = new SeatInfo(1, 10, CoachClass.FIRST);
    private static final SeatInfo SECOND_2 = new SeatInfo(2, 20, CoachClass.SECOND);
    private static final SeatInfo SECOND_3 = new SeatInfo(3, 20, CoachClass.SECOND);
    private static final SeatInfo THIRD_4 = new SeatInfo(4, 30, CoachClass.THIRD);
    private static final List<SeatInfo> SEATS = List.of(FIRST_1, SECOND_2, SECOND_3, THIRD_4);

    private static Journey journey(int from, int to) {
        return Journey.of(from, to, STOPS);
    }

    private static ReservationInfo booked(long seatId, int segment) {
        return new ReservationInfo(seatId, segment, false);
    }

    private static ReservationInfo held(long seatId, int segment) {
        return new ReservationInfo(seatId, segment, true);
    }

    /** The state of one seat for one journey. */
    private static SeatState stateOf(long seatId, Journey journey, ReservationInfo... reservations) {
        JourneyAvailability result = SegmentAvailability.compute(journey, SEATS, List.of(reservations), List.of());
        return result.seats().stream().filter(s -> s.seatId() == seatId).findFirst().orElseThrow().state();
    }

    /** Standing rows of one coach: capacity on every segment 0..2 and the given usage per segment. */
    private static List<StandingInfo> standing(long coachId, CoachClass coachClass, int capacity, int... usedPerSegment) {
        List<StandingInfo> rows = new ArrayList<>();
        for (int segment = 0; segment < usedPerSegment.length; segment++) {
            rows.add(new StandingInfo(coachId, coachClass, segment, capacity, usedPerSegment[segment]));
        }
        return rows;
    }

    private static JourneyAvailability withStanding(Journey journey, List<StandingInfo> rows) {
        return SegmentAvailability.compute(journey, SEATS, List.of(), rows);
    }

    // ---- seats ----------------------------------------------------------------------------------------------------

    @Test
    void aSeatTakenOnAMiddleSegment_isUnavailableForEveryJourneyThatCrossesIt() {
        ReservationInfo middle = booked(1, 1); // seat 1 is booked on segment 1 only (stop 1 to stop 2)

        assertThat(stateOf(1, journey(0, 3), middle)).isEqualTo(SeatState.BOOKED); // segments 0,1,2
        assertThat(stateOf(1, journey(0, 2), middle)).isEqualTo(SeatState.BOOKED); // segments 0,1
        assertThat(stateOf(1, journey(1, 3), middle)).isEqualTo(SeatState.BOOKED); // segments 1,2
        assertThat(stateOf(1, journey(1, 2), middle)).isEqualTo(SeatState.BOOKED); // segment 1
    }

    @Test
    void aSeatTakenOnAMiddleSegment_isStillAvailableForJourneysThatDoNotCrossIt() {
        ReservationInfo middle = booked(1, 1);

        assertThat(stateOf(1, journey(0, 1), middle)).isEqualTo(SeatState.AVAILABLE); // segment 0 only
        assertThat(stateOf(1, journey(2, 3), middle)).isEqualTo(SeatState.AVAILABLE); // segment 2 only
    }

    @Test
    void aSeatTakenOnlyOutsideTheJourney_isAvailable() {
        // Booked on the first and the last segment, the journey is the middle one: the two bookings do not overlap it.
        assertThat(stateOf(1, journey(1, 2), booked(1, 0), booked(1, 2))).isEqualTo(SeatState.AVAILABLE);
    }

    @Test
    void aJourneyOfOneSegment_looksAtThatSegmentOnly() {
        for (int taken = 0; taken < STOPS - 1; taken++) {
            for (int asked = 0; asked < STOPS - 1; asked++) {
                SeatState expected = asked == taken ? SeatState.HELD : SeatState.AVAILABLE;
                assertThat(stateOf(1, journey(asked, asked + 1), held(1, taken)))
                        .as("seat held on segment %d, journey of segment %d", taken, asked)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void theWholeTripJourney_needsEverySegmentFree() {
        for (int taken = 0; taken < STOPS - 1; taken++) {
            assertThat(stateOf(1, journey(0, STOPS - 1), booked(1, taken))).as("segment %d taken", taken).isEqualTo(SeatState.BOOKED);
        }
        assertThat(stateOf(1, journey(0, STOPS - 1))).isEqualTo(SeatState.AVAILABLE);
    }

    @Test
    void twoPassengersCanShareASeatWhenTheirJourneysDoNotOverlap() {
        // SRS 4.1, Cairo(0) - Tanta(1) - Alexandria(2): three stops, two segments.
        Journey cairoToTanta = Journey.of(0, 1, 3);
        Journey tantaToAlexandria = Journey.of(1, 2, 3);
        Journey cairoToAlexandria = Journey.of(0, 2, 3);
        List<ReservationInfo> bookedCairoToTanta = List.of(booked(1, 0));
        List<ReservationInfo> bookedTantaToAlexandria = List.of(booked(1, 1));

        assertThat(state(tantaToAlexandria, bookedCairoToTanta)).isEqualTo(SeatState.AVAILABLE);
        assertThat(state(cairoToAlexandria, bookedCairoToTanta)).isEqualTo(SeatState.BOOKED);
        assertThat(state(cairoToTanta, bookedTantaToAlexandria)).isEqualTo(SeatState.AVAILABLE);
    }

    private static SeatState state(Journey journey, List<ReservationInfo> reservations) {
        return SegmentAvailability.compute(journey, SEATS, reservations, List.of()).seats().get(0).state();
    }

    @Test
    void aHeldSeatIsHeld_andBookedWinsWhenBothAppearOnTheJourney() {
        ReservationInfo heldFirst = held(1, 0);
        ReservationInfo bookedSecond = booked(1, 1);

        assertThat(stateOf(1, journey(0, 1), heldFirst, bookedSecond)).isEqualTo(SeatState.HELD);
        assertThat(stateOf(1, journey(1, 2), heldFirst, bookedSecond)).isEqualTo(SeatState.BOOKED);
        assertThat(stateOf(1, journey(0, 2), heldFirst, bookedSecond)).isEqualTo(SeatState.BOOKED);
    }

    @Test
    void aReservationOfAnotherSeat_doesNotAffectThisOne() {
        JourneyAvailability result = SegmentAvailability.compute(journey(0, 3), SEATS, List.of(booked(2, 1)), List.of());

        assertThat(result.seats()).extracting(s -> s.seatId() + ":" + s.state())
                .containsExactly("1:AVAILABLE", "2:BOOKED", "3:AVAILABLE", "4:AVAILABLE");
    }

    @Test
    void theSeatsKeepTheirOrder_andCarryTheirCoachAndClass() {
        JourneyAvailability result = SegmentAvailability.compute(journey(0, 3), SEATS, List.of(), List.of());

        assertThat(result.seats()).extracting(s -> s.seatId() + "/" + s.coachId() + "/" + s.coachClass())
                .containsExactly("1/10/FIRST", "2/20/SECOND", "3/20/SECOND", "4/30/THIRD");
    }

    @Test
    void seatsLeft_countsOnlyAvailableSeats_perClass() {
        // Seat 2 held, seat 4 booked, both inside the journey; seat 3 is booked on a segment outside the journey.
        List<ReservationInfo> reservations = List.of(held(2, 1), booked(4, 2), booked(3, 0));

        JourneyAvailability result = SegmentAvailability.compute(journey(1, 3), SEATS, reservations, List.of());

        assertThat(result.seatsLeft()).containsExactlyInAnyOrderEntriesOf(Map.of(CoachClass.FIRST, 1, CoachClass.SECOND, 1, CoachClass.THIRD, 0));
    }

    @Test
    void aClassWithNoSeats_isNotListed() {
        JourneyAvailability result = SegmentAvailability.compute(journey(0, 3), List.of(FIRST_1), List.of(), List.of());

        assertThat(result.seatsLeft()).containsOnlyKeys(CoachClass.FIRST);
    }

    @Test
    void aTripWithoutSeatsOrStanding_hasNothingLeft() {
        JourneyAvailability result = SegmentAvailability.compute(journey(0, 3), List.of(), List.of(), List.of());

        assertThat(result.seats()).isEmpty();
        assertThat(result.standing()).isEmpty();
        assertThat(result.seatsLeft()).isEmpty();
        assertThat(result.standingLeft()).isEmpty();
    }

    // ---- standing places --------------------------------------------------------------------------------------------

    @Test
    void standingAtTheLimit_leavesNoPlaces() {
        JourneyAvailability result = withStanding(journey(0, 3), standing(20, CoachClass.SECOND, 10, 3, 10, 2));

        assertThat(result.standing()).containsExactly(new CoachStandingLeft(20, CoachClass.SECOND, 10, 0));
        assertThat(result.standingLeft()).containsEntry(CoachClass.SECOND, 0);
    }

    @Test
    void standingOneBelowTheLimit_leavesOnePlace() {
        JourneyAvailability result = withStanding(journey(0, 3), standing(20, CoachClass.SECOND, 10, 3, 9, 2));

        assertThat(result.standing().get(0).remaining()).isEqualTo(1);
        assertThat(result.standingLeft()).containsEntry(CoachClass.SECOND, 1);
    }

    @Test
    void standingFullOnlyOutsideTheJourney_leavesTheFullCapacity() {
        // Segment 0 is full, the journey uses segments 1 and 2 only.
        JourneyAvailability result = withStanding(journey(1, 3), standing(20, CoachClass.SECOND, 10, 10, 0, 0));

        assertThat(result.standing().get(0).remaining()).isEqualTo(10);
    }

    @Test
    void standingLeft_isTheMinimumOverTheSegmentsOfTheJourney() {
        List<StandingInfo> rows = standing(20, CoachClass.SECOND, 10, 2, 5, 3); // remaining 8, 5, 7

        assertThat(withStanding(journey(0, 3), rows).standing().get(0).remaining()).isEqualTo(5);
        assertThat(withStanding(journey(0, 1), rows).standing().get(0).remaining()).isEqualTo(8);
        assertThat(withStanding(journey(2, 3), rows).standing().get(0).remaining()).isEqualTo(7);
        assertThat(withStanding(journey(0, 2), rows).standing().get(0).remaining()).isEqualTo(5);
    }

    @Test
    void aFullMiddleSegment_blocksEveryJourneyThatIncludesIt() {
        // SRS acceptance 3: capacity 10 and 10 places used on one middle segment.
        List<StandingInfo> rows = standing(20, CoachClass.SECOND, 10, 0, 10, 0);

        for (Journey blocked : List.of(journey(0, 3), journey(0, 2), journey(1, 3), journey(1, 2))) {
            assertThat(withStanding(blocked, rows).standing().get(0).remaining()).as(blocked.toString()).isZero();
        }
        assertThat(withStanding(journey(0, 1), rows).standing().get(0).remaining()).isEqualTo(10);
        assertThat(withStanding(journey(2, 3), rows).standing().get(0).remaining()).isEqualTo(10);
    }

    @Test
    void standingOfSeveralCoaches_isSummedPerClassOfCoach_eachCoachByItsOwnMinimum() {
        List<StandingInfo> rows = new ArrayList<>();
        rows.addAll(standing(10, CoachClass.FIRST, 2, 0, 0, 0));    // 2 left
        rows.addAll(standing(20, CoachClass.SECOND, 5, 0, 5, 0));   // full on the middle segment: 0 left
        rows.addAll(standing(21, CoachClass.SECOND, 4, 1, 1, 1));   // 3 left

        JourneyAvailability result = withStanding(journey(0, 3), rows);

        // Coach 20 and coach 21 are separate counters: a passenger stands in one coach, so the places of coach 20 do not
        // make up for the missing ones in coach 21. The class total is 0 + 3, not "min over segments of the sum".
        assertThat(result.standingLeft()).containsExactlyInAnyOrderEntriesOf(Map.of(CoachClass.FIRST, 2, CoachClass.SECOND, 3));
        assertThat(result.standing()).extracting(CoachStandingLeft::coachId).containsExactly(10L, 20L, 21L);
    }

    @Test
    void aCoachWithZeroCapacity_hasNothingLeft() {
        JourneyAvailability result = withStanding(journey(0, 3), standing(20, CoachClass.SECOND, 0, 0, 0, 0));

        assertThat(result.standing().get(0).remaining()).isZero();
        assertThat(result.standingLeft()).containsEntry(CoachClass.SECOND, 0);
    }

    @Test
    void aSegmentOfTheJourneyWithoutACounterRow_countsAsNoCapacity() {
        // Rows exist for segments 0 and 2 only. A counter that is missing must never be read as "free".
        List<StandingInfo> rows = List.of(
                new StandingInfo(20, CoachClass.SECOND, 0, 10, 0),
                new StandingInfo(20, CoachClass.SECOND, 2, 10, 0));

        assertThat(withStanding(journey(0, 3), rows).standing().get(0).remaining()).isZero();
        assertThat(withStanding(journey(0, 1), rows).standing().get(0).remaining()).isEqualTo(10);
    }

    @Test
    void aTripWithoutStandingRows_listsNoStandingClass() {
        JourneyAvailability result = SegmentAvailability.compute(journey(0, 3), SEATS, List.of(), List.of());

        assertThat(result.standingLeft()).isEmpty();
    }

    @Test
    void seatsAndStanding_areIndependent() {
        JourneyAvailability result = SegmentAvailability.compute(journey(0, 3), SEATS, List.of(booked(2, 0)),
                standing(20, CoachClass.SECOND, 10, 0, 0, 0));

        assertThat(result.seats().get(1).state()).isEqualTo(SeatState.BOOKED);
        assertThat(result.standing().get(0).remaining()).isEqualTo(10);
    }

    // ---- the journey itself -------------------------------------------------------------------------------------------

    @Test
    void aJourneyUsesTheSegmentsFromTheBoardingStopUpToTheStopBeforeTheDestination() {
        Journey journey = journey(1, 3);

        assertThat(journey.includes(0)).isFalse();
        assertThat(journey.includes(1)).isTrue();
        assertThat(journey.includes(2)).isTrue();
        assertThat(journey.includes(3)).isFalse();
    }

    @Test
    void theWholeTripIsAValidJourney_andSoIsASingleSegment() {
        assertThat(Journey.of(0, STOPS - 1, STOPS)).isEqualTo(new Journey(0, 3));
        assertThat(Journey.of(2, 3, STOPS)).isEqualTo(new Journey(2, 3));
    }

    @Test
    void invalidJourneys_areRejected_namingTheField() {
        assertRejected(-1, 2, "from");
        assertRejected(2, 2, "to");   // the destination must come after the boarding stop
        assertRejected(3, 1, "to");
        assertRejected(0, 4, "to");   // the trip has stops 0 to 3
        assertRejected(5, 9, "from"); // a boarding stop beyond the last one is reported once, on from
    }

    private static void assertRejected(int from, int to, String field) {
        assertThatThrownBy(() -> Journey.of(from, to, STOPS))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).details()).extracting(ApiError.Detail::field).containsExactly(field));
    }
}
