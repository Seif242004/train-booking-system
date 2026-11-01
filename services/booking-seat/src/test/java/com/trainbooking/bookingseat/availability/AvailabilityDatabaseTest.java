package com.trainbooking.bookingseat.availability;

import static com.trainbooking.bookingseat.testsupport.TestJwts.passenger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.bookingseat.availability.model.SeatState;
import com.trainbooking.bookingseat.availability.model.TripAvailability;
import com.trainbooking.bookingseat.availability.service.AvailabilityService;
import com.trainbooking.bookingseat.common.CoachClass;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * The availability queries and the constraints of the inventory tables, against a real Oracle schema. The rule itself is
 * covered by SegmentAvailabilityTest; here the question is whether the SQL feeds it the right rows.
 */
class AvailabilityDatabaseTest extends DatabaseTest {

    private static final long TRIP = 7001;

    @Autowired
    AvailabilityService service;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    private Map<Long, SeatState> seatStates(int from, int to) {
        TripAvailability result = service.availability(TRIP, from, to);
        Map<Long, SeatState> states = new java.util.LinkedHashMap<>();
        result.availability().seats().forEach(s -> states.put(s.seatId(), s.state()));
        return states;
    }

    @Test
    void anEmptyTrip_hasEverythingAvailable_inSeatMapOrder() {
        TripAvailability result = service.availability(TRIP, 0, 3);

        assertThat(result.tripStatus()).isEqualTo("OPEN");
        assertThat(seatStates(0, 3)).containsExactly(
                Map.entry(1L, SeatState.AVAILABLE), Map.entry(2L, SeatState.AVAILABLE),
                Map.entry(3L, SeatState.AVAILABLE), Map.entry(4L, SeatState.AVAILABLE));
        assertThat(result.availability().seatsLeft()).containsEntry(CoachClass.FIRST, 1).containsEntry(CoachClass.SECOND, 2).containsEntry(CoachClass.THIRD, 1);
        assertThat(result.availability().standingLeft()).containsEntry(CoachClass.SECOND, 10).containsEntry(CoachClass.THIRD, 5);
    }

    @Test
    void aSeatTakenOnAMiddleSegment_isTakenForTheJourneysThatCrossIt_andFreeForTheOthers() {
        book(TRIP, 1, 1); // seat 1 booked on segment 1 (Giza to Tanta)

        assertThat(seatStates(0, 3).get(1L)).isEqualTo(SeatState.BOOKED); // Cairo to Alexandria
        assertThat(seatStates(1, 2).get(1L)).isEqualTo(SeatState.BOOKED); // Giza to Tanta
        assertThat(seatStates(0, 1).get(1L)).isEqualTo(SeatState.AVAILABLE); // Cairo to Giza
        assertThat(seatStates(2, 3).get(1L)).isEqualTo(SeatState.AVAILABLE); // Tanta to Alexandria
        assertThat(service.availability(TRIP, 0, 3).availability().seatsLeft()).containsEntry(CoachClass.FIRST, 0);
        assertThat(service.availability(TRIP, 0, 1).availability().seatsLeft()).containsEntry(CoachClass.FIRST, 1);
    }

    @Test
    void aSeatTakenOnlyOutsideTheJourney_isAvailable() {
        book(TRIP, 2, 0);
        book(TRIP, 2, 2);

        assertThat(seatStates(1, 2).get(2L)).isEqualTo(SeatState.AVAILABLE);
    }

    @Test
    void aHoldThatHasAlreadyExpired_isStillHeld_neverFree_untilTheSweeperReleasesIt() {
        hold(TRIP, 3, 1, -5); // expired five minutes ago, not released yet

        assertThat(seatStates(0, 3).get(3L)).isEqualTo(SeatState.HELD);
        assertThat(service.availability(TRIP, 0, 3).availability().seatsLeft()).containsEntry(CoachClass.SECOND, 1);
    }

    @Test
    void aLiveHoldIsHeld_andABookingWithoutExpiryIsBooked() {
        hold(TRIP, 2, 0, 5);
        book(TRIP, 3, 0);

        Map<Long, SeatState> states = seatStates(0, 1);
        assertThat(states.get(2L)).isEqualTo(SeatState.HELD);
        assertThat(states.get(3L)).isEqualTo(SeatState.BOOKED);
    }

    @Test
    void standingAtTheLimitOnAMiddleSegment_leavesNoPlacesForAJourneyThatIncludesIt() {
        useStanding(TRIP, 30, 1, 5); // coach 30 has 5 places, all used on segment 1

        assertThat(service.availability(TRIP, 0, 3).availability().standingLeft()).containsEntry(CoachClass.THIRD, 0).containsEntry(CoachClass.SECOND, 10);
        assertThat(service.availability(TRIP, 0, 1).availability().standingLeft()).containsEntry(CoachClass.THIRD, 5);
        assertThat(service.availability(TRIP, 2, 3).availability().standingLeft()).containsEntry(CoachClass.THIRD, 5);
    }

    @Test
    void standingTakesTheMinimumOverTheSegmentsOfTheJourney() {
        useStanding(TRIP, 20, 0, 2);
        useStanding(TRIP, 20, 1, 7);
        useStanding(TRIP, 20, 2, 4);

        assertThat(service.availability(TRIP, 0, 3).availability().standing())
                .filteredOn(c -> c.coachId() == 20).singleElement().satisfies(c -> {
                    assertThat(c.capacity()).isEqualTo(10);
                    assertThat(c.remaining()).isEqualTo(3); // segment 1 has the fewest left
                });
    }

    // ---- what the constraints guarantee -----------------------------------------------------------------------------

    @Test
    void theSameSeatOnTheSameSegmentCannotBeReservedTwice_ora00001() {
        book(TRIP, 1, 1);

        assertThatThrownBy(() -> hold(TRIP, 1, 1, 5))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("ORA-00001");
    }

    @Test
    void theSameSeatOnAnotherSegmentCanBeReserved() {
        book(TRIP, 1, 0);
        book(TRIP, 1, 1);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM seat_reservation WHERE trip_id = :t AND seat_id = 1").param("t", TRIP).query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    void aReservationOfASeatThatIsNotInTheCopyOfTheTrip_isRefused() {
        assertThatThrownBy(() -> book(TRIP, 999, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ORA-02291");
    }

    @Test
    void standingUsedCanNeverExceedTheCapacity_evenWithoutTheConditionalUpdate() {
        assertThatThrownBy(() -> useStanding(TRIP, 30, 0, 6)) // capacity is 5
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ORA-02290");
    }

    @Test
    void aConditionalUpdateThatWouldExceedTheCapacity_updatesNoRow() {
        useStanding(TRIP, 30, 0, 4);

        int updated = jdbc.sql("UPDATE standing_usage SET used = used + :qty WHERE trip_id = :t AND coach_id = 30 "
                        + "AND segment_index = 0 AND used + :qty <= capacity")
                .param("qty", 2).param("t", TRIP).update();

        assertThat(updated).isZero(); // the row count is how a hold finds out that the places are gone
    }

    // ---- through the HTTP endpoint ------------------------------------------------------------------------------------

    @Test
    void theEndpoint_answersTheSummaryOfAJourney() throws Exception {
        book(TRIP, 1, 1);
        useStanding(TRIP, 30, 1, 5);

        mvc.perform(get("/trips/{id}/availability", TRIP).param("from", "0").param("to", "3").param("view", "summary").with(passenger()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.seatsLeft.FIRST").value(0))
                .andExpect(jsonPath("$.summary.seatsLeft.SECOND").value(2))
                .andExpect(jsonPath("$.summary.seatsLeft.THIRD").value(1))
                .andExpect(jsonPath("$.summary.standingLeft.THIRD").value(0))
                .andExpect(jsonPath("$.summary.standingLeft.SECOND").value(10))
                .andExpect(jsonPath("$.seats").doesNotHaveJsonPath());

        mvc.perform(get("/trips/{id}/availability", TRIP).param("from", "0").param("to", "1").param("view", "summary").with(passenger()))
                .andExpect(jsonPath("$.summary.seatsLeft.FIRST").value(1))
                .andExpect(jsonPath("$.summary.standingLeft.THIRD").value(5));
    }

    @Test
    void theEndpoint_refusesAJourneyBeyondTheStopsOfTheTrip_andAnUnknownTrip() throws Exception {
        mvc.perform(get("/trips/{id}/availability", TRIP).param("from", "0").param("to", "4").with(passenger()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("to"));
        mvc.perform(get("/trips/{id}/availability", 424242).param("from", "0").param("to", "1").with(passenger()))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> service.availability(424242, 0, 1)).isInstanceOf(TripNotFoundException.class);
    }
}
