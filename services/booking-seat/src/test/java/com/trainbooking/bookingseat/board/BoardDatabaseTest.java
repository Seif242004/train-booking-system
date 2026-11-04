package com.trainbooking.bookingseat.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.bookingseat.board.model.BoardResponse;
import com.trainbooking.bookingseat.board.model.BoardTrip;
import com.trainbooking.bookingseat.board.model.BoardTripDetail;
import com.trainbooking.bookingseat.board.model.SeatCounts;
import com.trainbooking.bookingseat.board.model.TripState;
import com.trainbooking.bookingseat.board.service.BoardService;
import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.HoldRequest.StandingRequest;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import com.trainbooking.bookingseat.booking.service.CancellationService.Actor;
import com.trainbooking.bookingseat.booking.service.ConfirmationService;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
import com.trainbooking.bookingseat.testsupport.TestJwts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The counting rule of the admin trips board. The fixture trip (see DatabaseTest.insertTrip) leaves 2026-11-02 08:00 (+02:00) and arrives at
 * 10:55; it has FIRST seat 1, SECOND seats 2 and 3 with 10 standing places, THIRD seat 4 with 5 standing places (19 places in all).
 * The test clock starts on 2026-11-01 10:00Z, before the departure.
 */
class BoardDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9901;
    private static final Instant BEFORE_DEPARTURE = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant WHILE_RUNNING = Instant.parse("2026-11-02T07:00:00Z");   // 09:00 in Cairo
    private static final Instant AFTER_ARRIVAL = Instant.parse("2026-11-02T09:30:00Z");   // 11:30 in Cairo
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice");

    @Autowired
    BoardService board;
    @Autowired
    Holds holds;
    @Autowired
    ConfirmationService confirmations;
    @Autowired
    CancellationService cancellations;

    @BeforeEach
    void trip() {
        insertTrip(TRIP);
        clock.set(BEFORE_DEPARTURE);
    }

    private BoardTrip theTrip() {
        List<BoardTrip> trips = board.board("2026-11-01", "2026-11-03", null).trips();
        assertThat(trips).hasSize(1);
        return trips.get(0);
    }

    @Test
    void anUntouchedTrip_isAllFree_andUpcoming() {
        BoardTrip trip = theTrip();
        assertThat(trip.state()).isEqualTo(TripState.UPCOMING);
        assertThat(trip.full()).isFalse();
        assertThat(trip.first()).isEqualTo(new SeatCounts(1, 0, 0, 1));
        assertThat(trip.second()).isEqualTo(new SeatCounts(2, 0, 0, 2));
        assertThat(trip.third()).isEqualTo(new SeatCounts(1, 0, 0, 1));
        assertThat(trip.standing()).isEqualTo(new SeatCounts(15, 0, 0, 15));
        assertThat(trip.totals()).isEqualTo(new SeatCounts(19, 0, 0, 19));
        assertThat(trip.origin()).isEqualTo("Cairo");
        assertThat(trip.destination()).isEqualTo("Alexandria");
        assertThat(trip.departedAt()).isNull();
    }

    @Test
    void aRunningHold_isHeld_notFree() {
        hold(TRIP, 2, 0, 5);
        hold(TRIP, 2, 1, 5);
        hold(TRIP, 2, 2, 5);
        assertThat(theTrip().second()).isEqualTo(new SeatCounts(2, 0, 1, 1));
        assertThat(theTrip().totals().free()).isEqualTo(18);
    }

    @Test
    void anExpiredButUnreleasedHold_stillBlocksTheSeat_itIsHeldUntilTheSweeperReleasesIt() {
        hold(TRIP, 2, 0, -1);
        assertThat(theTrip().second()).isEqualTo(new SeatCounts(2, 0, 1, 1));
        // The sweeper's release deletes the reservation rows: the seat is free again.
        jdbc.sql("DELETE FROM seat_reservation WHERE trip_id = :t").param("t", TRIP).update();
        assertThat(theTrip().second()).isEqualTo(new SeatCounts(2, 0, 0, 2));
    }

    @Test
    void aSeatBookedOnOneSegmentOnly_isNotFreeForTheFullJourney() {
        book(TRIP, 2, 1);
        assertThat(theTrip().second()).isEqualTo(new SeatCounts(2, 1, 0, 1));
    }

    @Test
    void confirmedWinsOverHeld_whenASeatHasBoth() {
        book(TRIP, 2, 0);
        hold(TRIP, 2, 2, 5); // another passenger's hold on the last leg of the same seat
        assertThat(theTrip().second()).isEqualTo(new SeatCounts(2, 1, 0, 1));
    }

    @Test
    void aCancelledBooking_freesItsSeats() {
        BookingView booking = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of()));
        confirmations.confirm(booking.id());
        assertThat(theTrip().second()).isEqualTo(new SeatCounts(2, 1, 0, 1));
        cancellations.cancel(Actor.admin("admin-sub"), booking.id(), "Called support");
        assertThat(theTrip().second()).isEqualTo(new SeatCounts(2, 0, 0, 2));
    }

    @Test
    void standingPlaces_areCountedConfirmedHeldAndFree_fromTheirCapacity() {
        BookingView confirmed = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(), List.of(new StandingRequest(20L, 3))));
        confirmations.confirm(confirmed.id());
        holds.hold(new AuthenticatedUser("bob-sub", "bob@example.com", "Bob"), new HoldRequest(TRIP, 0, 3, List.of(), List.of(new StandingRequest(30L, 2))));
        assertThat(theTrip().standing()).isEqualTo(new SeatCounts(15, 3, 2, 10));
    }

    @Test
    void aStandingBookingOnPartOfTheJourney_reducesTheFullJourneyFreePlaces() {
        BookingView part = holds.hold(ALICE, new HoldRequest(TRIP, 1, 2, List.of(), List.of(new StandingRequest(30L, 4))));
        confirmations.confirm(part.id());
        assertThat(theTrip().standing()).isEqualTo(new SeatCounts(15, 4, 0, 11));
    }

    @Test
    void aTripWithNothingFreeInAnyClass_isFull() {
        for (long seat = 1; seat <= 4; seat++) {
            for (int segment = 0; segment < 3; segment++) {
                book(TRIP, seat, segment);
            }
        }
        for (int segment = 0; segment < 3; segment++) {
            useStanding(TRIP, 20, segment, 10);
            useStanding(TRIP, 30, segment, 5);
        }
        BoardTrip trip = theTrip();
        assertThat(trip.full()).isTrue();
        assertThat(trip.totals().free()).isZero();
        assertThat(board.board("2026-11-01", "2026-11-03", "FULL").trips()).hasSize(1);
        assertThat(board.board("2026-11-01", "2026-11-03", "FULL").summary().full()).isEqualTo(1);
    }

    @Test
    void oneFreeStandingPlace_meansNotFull() {
        for (long seat = 1; seat <= 4; seat++) {
            book(TRIP, seat, 0);
            book(TRIP, seat, 1);
            book(TRIP, seat, 2);
        }
        for (int segment = 0; segment < 3; segment++) {
            useStanding(TRIP, 20, segment, 10);
            useStanding(TRIP, 30, segment, 4);
        }
        assertThat(theTrip().full()).isFalse();
    }

    @Test
    void lifecycle_upcoming_departed_arrived_cancelled_withFinalNumbersOnceLeft() {
        book(TRIP, 1, 0);
        book(TRIP, 1, 1);
        book(TRIP, 1, 2);
        clock.set(WHILE_RUNNING);
        BoardTrip departed = theTrip();
        assertThat(departed.state()).isEqualTo(TripState.DEPARTED);
        assertThat(departed.departedAt()).isNotNull();
        assertThat(departed.totalSeats()).isEqualTo(19);
        assertThat(departed.confirmedAtDeparture()).isEqualTo(1);
        assertThat(departed.full()).isFalse();

        clock.set(AFTER_ARRIVAL);
        assertThat(theTrip().state()).isEqualTo(TripState.ARRIVED);
        assertThat(theTrip().confirmedAtDeparture()).isEqualTo(1);

        jdbc.sql("UPDATE trip SET status = 'CANCELLING' WHERE id = :t").param("t", TRIP).update();
        assertThat(theTrip().state()).isEqualTo(TripState.CANCELLED);
    }

    @Test
    void theStateFilter_keepsTheSummaryOverTheWholeRange() {
        insertTrip(TRIP + 1);
        jdbc.sql("UPDATE trip SET status = 'CANCELLED' WHERE id = :t").param("t", TRIP + 1).update();
        BoardResponse upcoming = board.board("2026-11-01", "2026-11-03", "UPCOMING");
        assertThat(upcoming.trips()).extracting(BoardTrip::tripId).containsExactly(TRIP);
        assertThat(upcoming.summary().trips()).isEqualTo(2);
        assertThat(upcoming.summary().cancelled()).isEqualTo(1);
        assertThat(board.board("2026-11-01", "2026-11-03", "CANCELLED,UPCOMING").trips()).hasSize(2);
    }

    @Test
    void theDefaultRange_isTheLast24HoursAndTheNextDays_andADepartedTripStaysForSevenDays() {
        clock.set(Instant.parse("2026-11-04T10:00:00Z"));
        assertThat(board.board(null, null, null).trips()).isEmpty();                    // left two days ago: outside the default
        assertThat(board.board("2026-10-28", "2026-11-04", null).trips()).hasSize(1);   // but a custom range up to 7 days back shows it
        clock.set(Instant.parse("2026-11-02T12:00:00Z"));
        assertThat(board.board(null, null, null).trips()).hasSize(1);
    }

    @Test
    void badInput_isRefused() {
        assertThatThrownBy(() -> board.board("yesterday", null, null)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> board.board("2026-11-05", "2026-11-01", null)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> board.board("2026-01-01", "2026-12-31", null)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> board.board(null, null, "SOLD_OUT")).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void detail_showsFreeHeldTaken_withAReferenceOnlyOnTakenSeats() {
        BookingView confirmed = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of()));
        confirmations.confirm(confirmed.id());
        holds.hold(new AuthenticatedUser("bob-sub", "bob@example.com", "Bob"), new HoldRequest(TRIP, 0, 3, List.of(3L), List.of()));
        BoardTripDetail detail = board.detail(TRIP);
        var byId = detail.seats().stream().collect(java.util.stream.Collectors.toMap(s -> s.seatId(), s -> s));
        assertThat(byId.get(2L).state()).isEqualTo("TAKEN");
        assertThat(byId.get(2L).reference()).isEqualTo(confirmed.reference());
        assertThat(byId.get(3L).state()).isEqualTo("HELD");
        assertThat(byId.get(3L).reference()).isNull();
        assertThat(byId.get(1L).state()).isEqualTo("FREE");
        assertThat(detail.standing()).hasSize(2);
        assertThatThrownBy(() -> board.detail(123456)).isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void theEndpoints_needTheAdminRole() throws Exception {
        mvc.perform(get("/admin/board/trips")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/board/trips").with(TestJwts.passenger())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/board/trips/" + TRIP).with(TestJwts.passenger())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/board/trips?from=2026-11-01&to=2026-11-03").with(TestJwts.admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.trips").value(1)).andExpect(jsonPath("$.trips[0].state").value("UPCOMING"));
        mvc.perform(get("/admin/board/trips/" + TRIP).with(TestJwts.admin())).andExpect(status().isOk()).andExpect(jsonPath("$.seats.length()").value(4));
        mvc.perform(get("/admin/board/trips/424242").with(TestJwts.admin())).andExpect(status().isNotFound());
        mvc.perform(get("/admin/board/trips?state=NOPE").with(TestJwts.admin())).andExpect(status().isBadRequest());
    }
}
