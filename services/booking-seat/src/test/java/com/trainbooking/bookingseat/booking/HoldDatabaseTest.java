package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.bookingseat.availability.model.SeatState;
import com.trainbooking.bookingseat.availability.service.AvailabilityService;
import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldConflictException;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.TripClosedException;
import com.trainbooking.bookingseat.booking.service.BookingQueryService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import com.trainbooking.bookingseat.live.SeatEvent;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Holding seats and standing places against a real Oracle schema (SRS FR-H1 to FR-H3, sections 4 and 5). The trip has 4 stops, so
 * segments 0, 1 and 2; seat 1 is FIRST, seats 2 and 3 SECOND, seat 4 THIRD; coach 20 (SECOND) has 10 standing places and coach 30
 * (THIRD) has 5. The fares per segment are FIRST 10, SECOND 5 and THIRD 3.
 */
class HoldDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9001;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice");
    private static final AuthenticatedUser BOB = new AuthenticatedUser("bob-sub", "bob@example.com", "Bob");

    @Autowired
    HoldService holds;

    @Autowired
    BookingQueryService queries;

    @Autowired
    AvailabilityService availability;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    private static HoldRequest seats(int from, int to, Long... seatIds) {
        return new HoldRequest(TRIP, from, to, List.of(seatIds), List.of());
    }

    private static HoldRequest standing(int from, int to, long coachId, int qty) {
        return new HoldRequest(TRIP, from, to, List.of(), List.of(new HoldRequest.StandingRequest(coachId, qty)));
    }

    private int usedStanding(long coachId, int segment) {
        return jdbc.sql("SELECT used FROM standing_usage WHERE trip_id = :t AND coach_id = :c AND segment_index = :s")
                .param("t", TRIP).param("c", coachId).param("s", segment).query(Integer.class).single();
    }

    private long version() {
        return jdbc.sql("SELECT version FROM trip_version WHERE trip_id = :t").param("t", TRIP).query(Long.class).single();
    }

    // ---- what a hold creates ------------------------------------------------------------------------------------------

    @Test
    void aHold_createsAPendingBooking_thatRunsOutExactlyFiveMinutesLater_byTheServersClock() {
        BookingView booking = holds.hold(ALICE, seats(0, 3, 1L, 2L));

        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.reference()).matches("TB-[A-HJ-NP-Z2-9]{6}");
        assertThat(booking.createdAt().toInstant()).isEqualTo(clock.instant());
        assertThat(booking.expiresAt().toInstant()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(booking.serverTime().toInstant()).isEqualTo(clock.instant());
        assertThat(booking.fromStop()).isZero();
        assertThat(booking.toStop()).isEqualTo(3);
        assertThat(booking.trip().from().stationName()).isEqualTo("Cairo");
        assertThat(booking.trip().to().stationName()).isEqualTo("Alexandria");
        assertThat(booking.items()).extracting(item -> item.type() + ":" + item.seatNumber()).containsExactly("SEAT:A1", "SEAT:B1");
    }

    @Test
    void aSeatIsReservedOnEverySegmentOfTheJourney_andOnNoOtherSegment() {
        holds.hold(ALICE, seats(1, 3, 2L)); // Giza to Alexandria: segments 1 and 2

        assertThat(jdbc.sql("SELECT segment_index FROM seat_reservation WHERE trip_id = :t AND seat_id = 2 ORDER BY segment_index").param("t", TRIP)
                .query(Integer.class).list()).containsExactly(1, 2);
        // The rows are a hold: they carry the expiry of the booking.
        assertThat(rows("seat_reservation WHERE expires_at IS NOT NULL")).isEqualTo(2);
    }

    @Test
    void theAmountIsWorkedOutOnTheServer_fromTheFaresOfTheJourney() {
        BookingView booking = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(1L, 2L), List.of(new HoldRequest.StandingRequest(30L, 2))));

        // seat 1 FIRST 3 x 10 = 30, seat 2 SECOND 3 x 5 = 15, two standing places THIRD 2 x 3 x 3 = 18
        assertThat(booking.amount()).isEqualByComparingTo("63.00");
        assertThat(booking.currency()).isEqualTo("EGP");
        assertThat(booking.items()).extracting(item -> item.type() + ":" + item.price().toPlainString())
                .containsExactly("SEAT:30.00", "SEAT:15.00", "STANDING:18.00");
        assertThat(jdbc.sql("SELECT amount FROM booking WHERE id = :id").param("id", booking.id()).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("63.00");
    }

    @Test
    void theUserIsTheKeycloakSubject_andTheEmailAndNameArePlainCopies() {
        BookingView booking = holds.hold(ALICE, seats(0, 1, 1L));

        var row = jdbc.sql("SELECT user_id, user_email, user_name FROM booking WHERE id = :id").param("id", booking.id())
                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3)).single();
        assertThat(row).isEqualTo("alice-sub|alice@example.com|Alice");
    }

    // ---- all or nothing ---------------------------------------------------------------------------------------------------

    @Test
    void aSeatTakenOnAMiddleSegment_refusesTheWholeHold_nothingIsHeld_andTheLostSeatIsNamed() {
        holds.hold(BOB, seats(1, 2, 2L)); // seat 2 on segment 1 only
        long versionBefore = version();
        committed.clear();

        assertThatThrownBy(() -> holds.hold(ALICE, seats(0, 3, 1L, 2L, 3L)))
                .isInstanceOfSatisfying(HoldConflictException.class, e -> assertThat(e.lost()).extracting(HoldConflictException.Lost::id).containsExactly(2L));

        assertThat(rows("seat_reservation WHERE booking_id <> (SELECT MIN(id) FROM booking)")).isZero(); // seats 1 and 3 were not held
        assertThat(rows("booking")).isEqualTo(1);
        assertThat(version()).isEqualTo(versionBefore);
        assertThat(committed.seatEvents()).isEmpty(); // nothing was announced: a rolled-back hold never reaches the clients
    }

    @Test
    void aSeatTakenOnlyOutsideTheJourney_doesNotBlockTheHold() {
        holds.hold(BOB, seats(0, 1, 2L)); // segment 0

        BookingView alice = holds.hold(ALICE, seats(1, 3, 2L)); // segments 1 and 2

        assertThat(alice.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void twoJourneysThatDoNotOverlapShareASeat_butOverlappingOnesDoNot() {
        holds.hold(ALICE, seats(0, 1, 2L));
        holds.hold(BOB, seats(1, 3, 2L)); // B to D: fine

        assertThatThrownBy(() -> holds.hold(new AuthenticatedUser("carol-sub", "c@example.com", "Carol"), seats(0, 3, 2L))).isInstanceOf(HoldConflictException.class);
        assertThatThrownBy(() -> holds.hold(new AuthenticatedUser("dan-sub", "d@example.com", "Dan"), seats(2, 3, 2L))).isInstanceOf(HoldConflictException.class);
    }

    @Test
    void aFailureOnTheStandingPlaces_releasesTheSeatsOfTheSameHold() {
        useStanding(TRIP, 30, 1, 5); // coach 30 is full on the middle segment
        HoldRequest request = new HoldRequest(TRIP, 0, 3, List.of(1L), List.of(new HoldRequest.StandingRequest(30L, 1)));

        assertThatThrownBy(() -> holds.hold(ALICE, request)).isInstanceOfSatisfying(HoldConflictException.class,
                e -> assertThat(e.lost()).extracting(HoldConflictException.Lost::type, HoldConflictException.Lost::id).containsExactly(org.assertj.core.groups.Tuple.tuple("STANDING", 30L)));

        assertThat(rows("seat_reservation")).isZero();
        assertThat(usedStanding(30, 0)).isZero(); // segment 0 was already taken when segment 1 failed: it was rolled back
        assertThat(usedStanding(30, 2)).isZero();
        assertThat(rows("booking")).isZero();
        assertThat(rows("standing_hold")).isZero();
    }

    // ---- standing places --------------------------------------------------------------------------------------------------

    @Test
    void standingPlaces_canBeHeldUpToTheLimit_andNotOneMore() {
        holds.hold(ALICE, standing(0, 3, 30, 5)); // all 5 places of coach 30, on all three segments

        assertThat(usedStanding(30, 0)).isEqualTo(5);
        assertThat(usedStanding(30, 1)).isEqualTo(5);
        assertThat(usedStanding(30, 2)).isEqualTo(5);
        assertThatThrownBy(() -> holds.hold(BOB, standing(0, 1, 30, 1))).isInstanceOf(HoldConflictException.class);
        assertThat(usedStanding(30, 0)).isEqualTo(5); // never above the capacity
    }

    @Test
    void aFullMiddleSegment_blocksEveryJourneyThatIncludesIt_butNotTheOthers() {
        useStanding(TRIP, 30, 1, 5);

        assertThatThrownBy(() -> holds.hold(ALICE, standing(0, 3, 30, 1))).isInstanceOf(HoldConflictException.class);
        assertThatThrownBy(() -> holds.hold(ALICE, standing(1, 2, 30, 1))).isInstanceOf(HoldConflictException.class);
        holds.hold(ALICE, standing(0, 1, 30, 1)); // only segment 0
        holds.hold(BOB, standing(2, 3, 30, 1)); // only segment 2

        assertThat(usedStanding(30, 0)).isEqualTo(1);
        assertThat(usedStanding(30, 1)).isEqualTo(5);
        assertThat(usedStanding(30, 2)).isEqualTo(1);
    }

    @Test
    void standingHoldsRememberWhereTheyAre_forTheSweeperAndTheCancellation() {
        BookingView booking = holds.hold(ALICE, standing(1, 3, 20, 2));

        var hold = jdbc.sql("SELECT coach_id, from_segment, to_segment, qty FROM standing_hold WHERE booking_id = :b").param("b", booking.id())
                .query((rs, n) -> rs.getLong(1) + ":" + rs.getInt(2) + "-" + rs.getInt(3) + ":" + rs.getInt(4)).single();
        assertThat(hold).isEqualTo("20:1-2:2");
    }

    // ---- an expired hold is still a hold ----------------------------------------------------------------------------------

    @Test
    void anExpiredButUnreleasedHold_stillBlocksTheSeat_isShownAsHeld_andIsNotPurgedByANewHold() {
        BookingView alice = holds.hold(ALICE, seats(0, 3, 2L));
        clock.advance(Duration.ofMinutes(6)); // past the expiry; the sweeper has not run

        assertThatThrownBy(() -> holds.hold(BOB, seats(0, 3, 2L))).isInstanceOf(HoldConflictException.class);

        assertThat(rows("seat_reservation WHERE booking_id = " + alice.id())).isEqualTo(3); // not purged
        assertThat(availability.availability(TRIP, 0, 3).availability().seats().stream().filter(s -> s.seatId() == 2).findFirst().orElseThrow().state())
                .isEqualTo(SeatState.HELD);
    }

    // ---- concurrency: the database decides --------------------------------------------------------------------------------

    @Test
    void manyUsersHoldingTheSameSeatAtTheSameTime_exactlyOneSucceeds() throws Exception {
        int users = 8;
        List<Callable<Object>> calls = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < users; i++) {
            AuthenticatedUser user = new AuthenticatedUser("user-" + i, "u" + i + "@example.com", "User " + i);
            calls.add(() -> {
                go.await();
                try {
                    return holds.hold(user, seats(0, 3, 1L));
                } catch (HoldConflictException e) {
                    return e;
                }
            });
        }
        List<Object> results = runTogether(calls, go);

        assertThat(results.stream().filter(r -> r instanceof BookingView)).hasSize(1);
        assertThat(results.stream().filter(r -> r instanceof HoldConflictException)).hasSize(users - 1);
        assertThat(rows("seat_reservation WHERE seat_id = 1")).isEqualTo(3); // one hold, three segments, nothing doubled
        assertThat(rows("booking")).isEqualTo(1);
    }

    @Test
    void manyUsersTakingTheLastStandingPlace_exactlyOneSucceeds_andTheCounterNeverExceedsTheCapacity() throws Exception {
        useStanding(TRIP, 30, 0, 4);
        useStanding(TRIP, 30, 1, 4);
        useStanding(TRIP, 30, 2, 4);
        int users = 8;
        List<Callable<Object>> calls = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < users; i++) {
            AuthenticatedUser user = new AuthenticatedUser("user-" + i, "u" + i + "@example.com", "User " + i);
            calls.add(() -> {
                go.await();
                try {
                    return holds.hold(user, standing(0, 3, 30, 1));
                } catch (HoldConflictException e) {
                    return e;
                }
            });
        }
        List<Object> results = runTogether(calls, go);

        assertThat(results.stream().filter(r -> r instanceof BookingView)).hasSize(1);
        for (int segment = 0; segment < 3; segment++) {
            assertThat(usedStanding(30, segment)).isEqualTo(5);
        }
    }

    @Test
    void manyHoldsOfDifferentSeatsAtTheSameTime_allSucceed_andTheVersionCountsEveryChange() throws Exception {
        List<Callable<Object>> calls = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        long[] seatIds = {1, 2, 3, 4};
        for (int i = 0; i < seatIds.length; i++) {
            AuthenticatedUser user = new AuthenticatedUser("user-" + i, "u" + i + "@example.com", "User " + i);
            long seatId = seatIds[i];
            calls.add(() -> {
                go.await();
                return holds.hold(user, seats(0, 3, seatId));
            });
        }
        runTogether(calls, go);

        assertThat(rows("booking")).isEqualTo(4);
        assertThat(version()).isEqualTo(4); // one SEAT_HELD event per hold, each with its own number
        assertThat(committed.seatEvents()).extracting(SeatEvent::version).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    private List<Object> runTogether(List<Callable<Object>> calls, CountDownLatch go) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(call));
            }
            go.countDown(); // all threads start at the same moment
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- events, version and audit -------------------------------------------------------------------------------------------

    @Test
    void aHoldAnnouncesWhatChanged_afterTheCommit_eachEventWithItsOwnVersion() {
        holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(1L), List.of(new HoldRequest.StandingRequest(30L, 2))));

        assertThat(committed.seatEvents()).hasSize(2);
        SeatEvent held = committed.seatEvents().get(0);
        assertThat(held.type()).isEqualTo(SeatEvent.SEAT_HELD);
        assertThat(held.seatIds()).containsExactly(1L);
        assertThat(held.segments()).containsExactly(0, 1, 2);
        assertThat(held.version()).isEqualTo(1);
        SeatEvent changed = committed.seatEvents().get(1);
        assertThat(changed.type()).isEqualTo(SeatEvent.STANDING_CHANGED);
        assertThat(changed.coachId()).isEqualTo(30L);
        assertThat(changed.delta()).isEqualTo(-2);
        assertThat(changed.version()).isEqualTo(2);
        assertThat(version()).isEqualTo(2);
    }

    @Test
    void everyHoldIsWrittenToTheAuditLog_withTheUser() {
        holds.hold(ALICE, seats(0, 1, 1L));

        assertThat(jdbc.sql("SELECT admin_id || ':' || action FROM audit_log").query(String.class).list()).containsExactly("alice-sub:HOLD");
    }

    // ---- what is refused before anything is held ---------------------------------------------------------------------------

    @Test
    void aTripThatIsBeingCancelledOrIsCancelled_takesNoHolds() {
        jdbc.sql("UPDATE trip SET status = 'CANCELLING' WHERE id = :t").param("t", TRIP).update();

        assertThatThrownBy(() -> holds.hold(ALICE, seats(0, 1, 1L))).isInstanceOfSatisfying(TripClosedException.class, e -> assertThat(e.code()).isEqualTo("trip_closed"));
        assertThat(rows("seat_reservation")).isZero();
    }

    @Test
    void aTrainThatHasLeftTheBoardingStop_cannotBeBoarded_butALaterStopCan() {
        clock.set(Instant.parse("2026-11-02T06:30:00Z")); // 08:30 in Cairo: the train left stop 0 at 08:00 and leaves stop 1 at 09:00

        assertThatThrownBy(() -> holds.hold(ALICE, seats(0, 3, 1L))).isInstanceOfSatisfying(TripClosedException.class, e -> assertThat(e.code()).isEqualTo("trip_departed"));
        assertThat(holds.hold(ALICE, seats(1, 3, 1L)).status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void anUnknownTrip_isNotFound_andAJourneyBeyondTheStopsIsRefused() {
        assertThatThrownBy(() -> holds.hold(ALICE, new HoldRequest(424242L, 0, 1, List.of(1L), List.of()))).isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> holds.hold(ALICE, seats(0, 4, 1L))).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> holds.hold(ALICE, seats(2, 2, 1L))).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void badSelections_areRefusedNamingTheField_andNothingIsHeld() {
        assertThat(problemsOf(new HoldRequest(TRIP, 0, 1, List.of(), List.of()))).containsExactly("seatIds");
        assertThat(problemsOf(seats(0, 1, 999L))).containsExactly("seatIds[0]"); // a seat that is not on this trip
        assertThat(problemsOf(seats(0, 1, 1L, 1L))).containsExactly("seatIds[1]"); // listed twice
        assertThat(problemsOf(standing(0, 1, 10, 1))).containsExactly("standing[0].coachId"); // FIRST coach: no standing places
        assertThat(problemsOf(standing(0, 1, 999, 1))).containsExactly("standing[0].coachId");
        assertThat(rows("booking")).isZero();
    }

    private List<String> problemsOf(HoldRequest request) {
        try {
            holds.hold(ALICE, request);
            throw new AssertionError("the hold should have been refused");
        } catch (RequestValidationException e) {
            return e.details().stream().map(ApiError.Detail::field).toList();
        }
    }

    // ---- who can read a booking ---------------------------------------------------------------------------------------------

    @Test
    void aBookingCanBeReadOnlyByItsOwner_forAnyoneElseItDoesNotExist() {
        BookingView booking = holds.hold(ALICE, seats(0, 1, 1L));

        assertThat(queries.viewOwn(booking.id(), "alice-sub").reference()).isEqualTo(booking.reference());
        assertThatThrownBy(() -> queries.viewOwn(booking.id(), "bob-sub")).isInstanceOf(BookingNotFoundException.class);
        assertThatThrownBy(() -> queries.viewOwn(987654, "alice-sub")).isInstanceOf(BookingNotFoundException.class);
    }

    // ---- the review: what the token must have, the boundary of the departure, and history ---------------------------------

    @Test
    void aTokenWithoutAnEmailAddress_cannotHold_becauseTheTicketCouldNeverBeSent() {
        assertThatThrownBy(() -> holds.hold(new AuthenticatedUser("nomail-sub", null, "No Mail"), seats(0, 1, 1L)))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> assertThat(e.details()).extracting(ApiError.Detail::field).containsExactly("email"));
        assertThat(rows("booking")).isZero();
    }

    @Test
    void aVeryLongName_isCutToFitTheColumn_insteadOfFailingTheHold() {
        BookingView booking = holds.hold(new AuthenticatedUser("long-sub", "long@example.com", "N".repeat(300)), seats(0, 1, 1L));

        assertThat(jdbc.sql("SELECT LENGTH(user_name) FROM booking WHERE id = :id").param("id", booking.id()).query(Integer.class).single()).isEqualTo(200);
    }

    @Test
    void aTrainThatLeavesExactlyNow_hasLeft() {
        clock.set(Instant.parse("2026-11-02T06:00:00Z")); // 08:00 in Cairo, the departure of stop 0

        assertThatThrownBy(() -> holds.hold(ALICE, seats(0, 3, 1L))).isInstanceOfSatisfying(TripClosedException.class, e -> assertThat(e.code()).isEqualTo("trip_departed"));
        clock.set(Instant.parse("2026-11-02T05:59:59Z"));
        assertThat(holds.hold(ALICE, seats(0, 3, 1L)).status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void aBookingKeepsWhatThePassengerWasShown_evenIfALaterSnapshotChangesTheSeatsStopsAndCoaches() {
        BookingView booking = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of(new HoldRequest.StandingRequest(30L, 1))));
        jdbc.sql("UPDATE booking SET status = 'EXPIRED' WHERE id = :id").param("id", booking.id()).update();
        jdbc.sql("DELETE FROM seat_reservation").update();
        // A snapshot that renames seats and coaches, removes a stop and a seat: nothing of the booking may change or fail.
        jdbc.sql("DELETE FROM trip_stop WHERE trip_id = :t AND stop_index = 3").param("t", TRIP).update();
        jdbc.sql("UPDATE trip_coach SET code = 'ZZ' WHERE trip_id = :t").param("t", TRIP).update();
        jdbc.sql("DELETE FROM trip_seat WHERE trip_id = :t AND seat_id = 2").param("t", TRIP).update();

        BookingView view = queries.viewOwn(booking.id(), "alice-sub");

        assertThat(view.items()).extracting(item -> item.type() + ":" + item.seatNumber() + ":" + item.coachCode() + ":" + item.coachClass())
                .containsExactly("SEAT:B1:B:SECOND", "STANDING:null:C:THIRD");
        assertThat(view.trip().to().stationName()).isEqualTo("?"); // the stop is gone: shown as unknown, not a failure
    }
}
