package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.bookingseat.availability.model.SeatState;
import com.trainbooking.bookingseat.availability.service.AvailabilityService;
import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldConflictException;
import com.trainbooking.bookingseat.booking.model.HoldExpiredException;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.PickRequest;
import com.trainbooking.bookingseat.booking.model.ReleaseRequest;
import com.trainbooking.bookingseat.booking.model.TripClosedException;
import com.trainbooking.bookingseat.booking.service.BookingQueryService;
import com.trainbooking.bookingseat.booking.service.ExpiryService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import com.trainbooking.bookingseat.live.SeatEvent;
import com.trainbooking.bookingseat.live.UserEvent;
import com.trainbooking.bookingseat.payment.PaymentPort.CancelOutcome;
import com.trainbooking.bookingseat.payment.PaymentUnavailableException;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
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
import org.springframework.dao.DuplicateKeyException;

/**
 * Picking and releasing seats against a real Oracle schema (Flow 3, SRS FR-H1 to FR-H7, sections 4 and 5). The hold starts at the first
 * pick; further picks join it and never extend its 5 minutes; deselecting releases at once; the last release ends the hold; a pick
 * that loses a race fails alone. The trip has 4 stops, so segments 0, 1 and 2; seat 1 is FIRST, seats 2 and 3 SECOND, seat 4 THIRD;
 * coach 20 (SECOND) has 10 standing places and coach 30 (THIRD) has 5. The fares per segment are FIRST 10, SECOND 5 and THIRD 3.
 */
class PickDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9001;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice");
    private static final AuthenticatedUser BOB = new AuthenticatedUser("bob-sub", "bob@example.com", "Bob");

    @Autowired
    HoldService service;

    @Autowired
    Holds holds;

    @Autowired
    BookingQueryService queries;

    @Autowired
    AvailabilityService availability;

    @Autowired
    ExpiryService expiry;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    private static PickRequest seat(int from, int to, long seatId) {
        return new PickRequest(TRIP, from, to, seatId, null, null);
    }

    private static PickRequest standing(int from, int to, long coachId, int qty) {
        return new PickRequest(TRIP, from, to, null, coachId, qty);
    }

    private static HoldRequest seats(int from, int to, Long... seatIds) {
        return new HoldRequest(TRIP, from, to, List.of(seatIds), List.of());
    }

    private static HoldRequest standingOnly(int from, int to, long coachId, int qty) {
        return new HoldRequest(TRIP, from, to, List.of(), List.of(new HoldRequest.StandingRequest(coachId, qty)));
    }

    private int usedStanding(long coachId, int segment) {
        return jdbc.sql("SELECT used FROM standing_usage WHERE trip_id = :t AND coach_id = :c AND segment_index = :s")
                .param("t", TRIP).param("c", coachId).param("s", segment).query(Integer.class).single();
    }

    private long version() {
        return jdbc.sql("SELECT version FROM trip_version WHERE trip_id = :t").param("t", TRIP).query(Long.class).single();
    }

    private String statusOf(long bookingId) {
        return jdbc.sql("SELECT status FROM booking WHERE id = :id").param("id", bookingId).query(String.class).single();
    }

    // ---- the first pick starts the hold ----------------------------------------------------------------------------------

    @Test
    void theFirstPick_startsTheHold_andItsFiveMinutesStartNow() {
        HoldService.PickResult result = service.pick(ALICE, seat(0, 3, 2));
        BookingView booking = result.booking();

        assertThat(result.started()).isTrue();
        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.reference()).matches("TB-[A-HJ-NP-Z2-9]{6}");
        assertThat(booking.createdAt().toInstant()).isEqualTo(clock.instant());
        assertThat(booking.expiresAt().toInstant()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(booking.serverTime().toInstant()).isEqualTo(clock.instant());
        assertThat(booking.trip().from().stationName()).isEqualTo("Cairo");
        assertThat(booking.trip().to().stationName()).isEqualTo("Alexandria");
        assertThat(booking.items()).extracting(item -> item.type() + ":" + item.seatNumber()).containsExactly("SEAT:B1");
        assertThat(rows("booking")).isEqualTo(1);
    }

    @Test
    void aSeatIsReservedOnEverySegmentOfTheJourney_andOnNoOtherSegment_withTheExpiryOfTheHold() {
        BookingView booking = service.pick(ALICE, seat(1, 3, 2)).booking(); // Giza to Alexandria: segments 1 and 2

        assertThat(jdbc.sql("SELECT segment_index FROM seat_reservation WHERE trip_id = :t AND seat_id = 2 ORDER BY segment_index").param("t", TRIP)
                .query(Integer.class).list()).containsExactly(1, 2);
        assertThat(rows("seat_reservation WHERE expires_at = (SELECT expires_at FROM booking WHERE id = " + booking.id() + ")")).isEqualTo(2);
    }

    @Test
    void theUserIsTheKeycloakSubject_andTheEmailAndNameArePlainCopies() {
        BookingView booking = service.pick(ALICE, seat(0, 1, 1)).booking();

        var row = jdbc.sql("SELECT user_id, user_email, user_name FROM booking WHERE id = :id").param("id", booking.id())
                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3)).single();
        assertThat(row).isEqualTo("alice-sub|alice@example.com|Alice");
    }

    // ---- further picks join the hold and never extend the timer -------------------------------------------------------------

    @Test
    void furtherPicksJoinTheSameBooking_andTheTimerIsNeverExtended() {
        BookingView first = service.pick(ALICE, seat(0, 3, 1)).booking();
        clock.advance(Duration.ofMinutes(3));

        HoldService.PickResult second = service.pick(ALICE, seat(0, 3, 2));
        HoldService.PickResult third = service.pick(ALICE, standing(0, 3, 30, 2));

        assertThat(second.started()).isFalse();
        assertThat(second.booking().id()).isEqualTo(first.id());
        assertThat(third.booking().id()).isEqualTo(first.id());
        assertThat(third.booking().expiresAt()).isEqualTo(first.expiresAt()); // the five minutes of the FIRST pick
        assertThat(rows("booking")).isEqualTo(1);
        // The seat picked later carries the expiry of the hold, not five minutes from its own pick.
        assertThat(rows("seat_reservation WHERE expires_at <> (SELECT expires_at FROM booking WHERE id = " + first.id() + ")")).isZero();
        assertThat(third.booking().items()).extracting(item -> item.type() + ":" + item.price().toPlainString()).containsExactly("SEAT:30.00", "SEAT:15.00", "STANDING:18.00");
        assertThat(third.booking().amount()).isEqualByComparingTo("63.00"); // worked out on the server: 30 + 15 + 2 x 3 x 3
    }

    @Test
    void everythingPickedLaterRunsOutWithTheFirstPick() {
        BookingView first = service.pick(ALICE, seat(0, 3, 1)).booking();
        clock.advance(Duration.ofMinutes(4));
        service.pick(ALICE, seat(0, 3, 2));
        service.pick(ALICE, standing(0, 3, 30, 1));

        clock.advance(Duration.ofMinutes(1)); // five minutes after the first pick, one minute after the last
        expiry.sweep();

        assertThat(statusOf(first.id())).isEqualTo("EXPIRED");
        assertThat(rows("seat_reservation")).isZero();
        assertThat(usedStanding(30, 0)).isZero();
    }

    @Test
    void aPickInAHoldThatRanOut_isRefusedWith410_beforeTheSweeperHasReleasedIt() {
        service.pick(ALICE, seat(0, 3, 1));
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThatThrownBy(() -> service.pick(ALICE, seat(0, 3, 2))).isInstanceOf(HoldExpiredException.class);
        assertThat(rows("seat_reservation WHERE seat_id = 2")).isZero();
    }

    @Test
    void pickingTheSameSeatAgain_changesNothing_andPickingStandingAgainAddsPlaces() {
        service.pick(ALICE, seat(0, 3, 1));
        BookingView again = service.pick(ALICE, seat(0, 3, 1)).booking();
        assertThat(again.items()).hasSize(1);
        assertThat(rows("seat_reservation WHERE seat_id = 1")).isEqualTo(3);

        service.pick(ALICE, standing(0, 3, 30, 1));
        BookingView more = service.pick(ALICE, standing(0, 3, 30, 2)).booking();

        assertThat(more.items().stream().filter(i -> i.type().equals("STANDING")).findFirst().orElseThrow().qty()).isEqualTo(3);
        assertThat(usedStanding(30, 1)).isEqualTo(3);
        assertThat(rows("standing_hold")).isEqualTo(1);
    }

    @Test
    void oneActiveHoldPerUserAndTrip_aPickForAnotherJourneyIsRefused() {
        service.pick(ALICE, seat(0, 3, 1));

        assertThatThrownBy(() -> service.pick(ALICE, seat(1, 3, 2)))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("hold_for_another_journey"));
        assertThat(rows("seat_reservation WHERE seat_id = 2")).isZero();
    }

    @Test
    void theDatabaseItselfRefusesASecondActiveHoldOfTheSameUserOnTheSameTrip() {
        service.pick(ALICE, seat(0, 3, 1));

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO booking (reference, user_id, trip_id, from_stop, to_stop, status, amount, expires_at, created_at) "
                        + "VALUES ('TB-DUPLIC', 'alice-sub', :t, 0, 1, 'PENDING_PAYMENT', 0, SYSTIMESTAMP, SYSTIMESTAMP)").param("t", TRIP).update())
                .isInstanceOf(DuplicateKeyException.class);
        // A finished booking of the same user and trip is no problem: only a hold counts.
        jdbc.sql("INSERT INTO booking (reference, user_id, trip_id, from_stop, to_stop, status, amount, expires_at, created_at) "
                + "VALUES ('TB-OLDONE', 'alice-sub', :t, 0, 1, 'EXPIRED', 0, SYSTIMESTAMP, SYSTIMESTAMP)").param("t", TRIP).update();
    }

    @Test
    void theSameUserCanHoldOnAnotherTrip_andTwoUsersCanHoldOnTheSameTrip() {
        insertTrip(9002);

        service.pick(ALICE, seat(0, 3, 1));
        service.pick(ALICE, new PickRequest(9002L, 0, 3, 1L, null, null));
        service.pick(BOB, seat(0, 3, 2));

        assertThat(rows("booking WHERE status = 'PENDING_PAYMENT'")).isEqualTo(3);
    }

    // ---- deselecting releases at once ---------------------------------------------------------------------------------------

    @Test
    void deselectingASeat_releasesItAtOnce_andTheHoldKeepsItsTimerAndItsOtherSeats() {
        BookingView hold = holds.hold(ALICE, seats(0, 3, 1L, 2L));
        committed.clear();
        clock.advance(Duration.ofMinutes(1));

        BookingView after = service.release(ALICE, hold.id(), new ReleaseRequest(1L, null, null));

        assertThat(after.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(after.expiresAt()).isEqualTo(hold.expiresAt()); // never extended, never shortened
        assertThat(after.items()).extracting(item -> item.seatNumber()).containsExactly("B1");
        assertThat(after.amount()).isEqualByComparingTo("15.00");
        assertThat(rows("seat_reservation WHERE seat_id = 1")).isZero();
        assertThat(rows("seat_reservation WHERE seat_id = 2")).isEqualTo(3);
        assertThat(committed.seatEvents()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(SeatEvent.SEAT_RELEASED);
            assertThat(event.seatIds()).containsExactly(1L);
        });
        // Anyone else can pick the seat now.
        assertThat(service.pick(BOB, seat(0, 3, 1)).booking().status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void releasingTheLastSeat_endsTheHold_abandoned_andEverythingIsFreeAgain() {
        BookingView hold = service.pick(ALICE, seat(0, 3, 1)).booking();
        committed.clear();

        BookingView after = service.release(ALICE, hold.id(), new ReleaseRequest(1L, null, null));

        assertThat(after.status()).isEqualTo(BookingStatus.ABANDONED);
        assertThat(rows("seat_reservation")).isZero();
        assertThat(committed.seatEvents()).extracting(SeatEvent::type).containsExactly(SeatEvent.SEAT_RELEASED);
        assertThat(committed.userEvents()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(UserEvent.HOLD_EXPIRED);
            assertThat(event.reason()).isEqualTo("RELEASED");
        });
        // The user can start a new hold afterwards: a fresh booking with a fresh five minutes.
        clock.advance(Duration.ofMinutes(2));
        BookingView next = service.pick(ALICE, seat(0, 3, 1)).booking();
        assertThat(next.id()).isNotEqualTo(hold.id());
        assertThat(next.expiresAt().toInstant()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
    }

    @Test
    void releasingStandingPlaces_givesThemBack_andReleasingTheLastOnesEndsTheHold() {
        BookingView hold = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(1L), List.of(new HoldRequest.StandingRequest(30L, 3))));

        BookingView partly = service.release(ALICE, hold.id(), new ReleaseRequest(null, 30L, 2));
        assertThat(usedStanding(30, 0)).isEqualTo(1);
        assertThat(partly.items().stream().filter(i -> i.type().equals("STANDING")).findFirst().orElseThrow().qty()).isEqualTo(1);
        assertThat(partly.amount()).isEqualByComparingTo("39.00"); // 30 for the seat + 1 place x 9

        service.release(ALICE, hold.id(), new ReleaseRequest(null, 30L, 1));
        assertThat(usedStanding(30, 0) + usedStanding(30, 1) + usedStanding(30, 2)).isZero();
        assertThat(rows("standing_hold")).isZero();

        BookingView last = service.release(ALICE, hold.id(), new ReleaseRequest(1L, null, null));
        assertThat(last.status()).isEqualTo(BookingStatus.ABANDONED);
    }

    @Test
    void releaseAll_endsTheHold_atOnce() {
        BookingView hold = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(1L, 2L), List.of(new HoldRequest.StandingRequest(30L, 2))));

        BookingView after = service.releaseAll(ALICE, hold.id());

        assertThat(after.status()).isEqualTo(BookingStatus.ABANDONED);
        assertThat(rows("seat_reservation")).isZero();
        assertThat(usedStanding(30, 1)).isZero();
        assertThat(jdbc.sql("SELECT action FROM audit_log ORDER BY id").query(String.class).list()).contains("HOLD", "PICK", "ABANDON");
    }

    @Test
    void aReleaseThatMakesNoSense_isRefused() {
        BookingView hold = service.pick(ALICE, seat(0, 3, 1)).booking();

        assertThatThrownBy(() -> service.release(ALICE, hold.id(), new ReleaseRequest(2L, null, null)))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("not_picked"));
        assertThatThrownBy(() -> service.release(ALICE, hold.id(), new ReleaseRequest(null, 30L, 1)))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("not_picked"));
        assertThatThrownBy(() -> service.release(BOB, hold.id(), new ReleaseRequest(1L, null, null))).isInstanceOf(BookingNotFoundException.class); // not Bob's
        assertThatThrownBy(() -> service.release(ALICE, hold.id(), new ReleaseRequest(null, null, null))).isInstanceOf(RequestValidationException.class);
        assertThat(rows("seat_reservation WHERE seat_id = 1")).isEqualTo(3); // nothing was released
    }

    @Test
    void aReleaseAfterTheTimeRanOut_isRefusedWith410_andOnlyAHoldCanBeReleased() {
        BookingView hold = service.pick(ALICE, seat(0, 3, 1)).booking();
        clock.advance(Duration.ofMinutes(6));

        assertThatThrownBy(() -> service.release(ALICE, hold.id(), new ReleaseRequest(1L, null, null))).isInstanceOf(HoldExpiredException.class);
        assertThatThrownBy(() -> service.releaseAll(ALICE, hold.id())).isInstanceOf(HoldExpiredException.class);
        expiry.sweep();
        assertThatThrownBy(() -> service.releaseAll(ALICE, hold.id()))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("not_a_hold"));
    }

    // ---- a pick that loses a race fails alone ---------------------------------------------------------------------------------

    @Test
    void aPickThatLosesARace_failsAlone_theSeatsAlreadyHeldStayHeld() {
        BookingView hold = service.pick(ALICE, seat(0, 3, 1)).booking();
        service.pick(BOB, seat(1, 2, 2)); // seat 2 on the middle segment only
        long versionBefore = version();
        committed.clear();

        assertThatThrownBy(() -> service.pick(ALICE, seat(0, 3, 2)))
                .isInstanceOfSatisfying(HoldConflictException.class, e -> assertThat(e.lost()).extracting(HoldConflictException.Lost::id).containsExactly(2L));

        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(rows("seat_reservation WHERE seat_id = 1")).isEqualTo(3); // the first pick is still held
        assertThat(queries.viewOwn(hold.id(), "alice-sub").items()).hasSize(1);
        assertThat(queries.viewOwn(hold.id(), "alice-sub").amount()).isEqualByComparingTo("30.00");
        assertThat(rows("seat_reservation WHERE seat_id = 2 AND segment_index <> 1")).isZero(); // not a trace of the failed pick
        assertThat(version()).isEqualTo(versionBefore);
        assertThat(committed.seatEvents()).isEmpty(); // nothing was announced
    }

    @Test
    void aFirstPickThatLosesARace_createsNoHold() {
        service.pick(BOB, seat(0, 3, 1));

        assertThatThrownBy(() -> service.pick(ALICE, seat(0, 3, 1))).isInstanceOf(HoldConflictException.class);

        assertThat(rows("booking WHERE user_id = 'alice-sub'")).isZero();
    }

    @Test
    void aStandingPickThatFails_keepsTheSeatsAndLeavesTheCountersUntouched() {
        BookingView hold = service.pick(ALICE, seat(0, 3, 1)).booking();
        useStanding(TRIP, 30, 1, 5); // coach 30 is full on the middle segment

        assertThatThrownBy(() -> service.pick(ALICE, standing(0, 3, 30, 1))).isInstanceOfSatisfying(HoldConflictException.class,
                e -> assertThat(e.lost()).extracting(HoldConflictException.Lost::type, HoldConflictException.Lost::id).containsExactly(org.assertj.core.groups.Tuple.tuple("STANDING", 30L)));

        assertThat(rows("seat_reservation WHERE seat_id = 1")).isEqualTo(3);
        assertThat(usedStanding(30, 0)).isZero(); // segment 0 was taken before segment 1 failed: it was rolled back
        assertThat(usedStanding(30, 2)).isZero();
        assertThat(rows("standing_hold")).isZero();
        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void aSeatTakenOnlyOutsideTheJourney_doesNotBlockThePick_andAdjacentJourneysShareASeat() {
        service.pick(BOB, seat(0, 1, 2));

        assertThat(service.pick(ALICE, seat(1, 3, 2)).booking().status()).isEqualTo(BookingStatus.PENDING_PAYMENT); // B to D next to A to B
        assertThatThrownBy(() -> service.pick(new AuthenticatedUser("carol-sub", "c@example.com", "Carol"), seat(0, 3, 2))).isInstanceOf(HoldConflictException.class);
        assertThatThrownBy(() -> service.pick(new AuthenticatedUser("dan-sub", "d@example.com", "Dan"), seat(2, 3, 2))).isInstanceOf(HoldConflictException.class);
    }

    @Test
    void standingPlaces_canBePickedUpToTheLimit_andNotOneMore_andAFullMiddleSegmentBlocksEveryJourneyThroughIt() {
        service.pick(ALICE, standing(0, 3, 30, 5)); // all 5 places, on all three segments
        assertThatThrownBy(() -> service.pick(BOB, standing(0, 1, 30, 1))).isInstanceOf(HoldConflictException.class);
        assertThat(usedStanding(30, 0)).isEqualTo(5); // never above the capacity

        useStanding(TRIP, 20, 1, 10); // coach 20 full on the middle segment only
        assertThatThrownBy(() -> service.pick(BOB, standing(0, 3, 20, 1))).isInstanceOf(HoldConflictException.class);
        assertThatThrownBy(() -> service.pick(BOB, standing(1, 2, 20, 1))).isInstanceOf(HoldConflictException.class);
        service.pick(BOB, standing(0, 1, 20, 1)); // only segment 0
        assertThat(usedStanding(20, 0)).isEqualTo(1);
    }

    // ---- an expired hold is still a hold ----------------------------------------------------------------------------------

    @Test
    void anExpiredButUnreleasedHold_stillBlocksTheSeat_isShownAsHeld_andIsNotPurgedByANewPick() {
        BookingView alice = service.pick(ALICE, seat(0, 3, 2)).booking();
        clock.advance(Duration.ofMinutes(6)); // past the expiry; the sweeper has not run

        assertThatThrownBy(() -> service.pick(BOB, seat(0, 3, 2))).isInstanceOf(HoldConflictException.class);

        assertThat(rows("seat_reservation WHERE booking_id = " + alice.id())).isEqualTo(3); // not purged
        assertThat(availability.availability(TRIP, 0, 3).availability().seats().stream().filter(s -> s.seatId() == 2).findFirst().orElseThrow().state())
                .isEqualTo(SeatState.HELD);
    }

    // ---- concurrency: the database decides --------------------------------------------------------------------------------

    @Test
    void manyUsersPickingTheSameSeatAtTheSameTime_exactlyOneSucceeds() throws Exception {
        List<Callable<Object>> calls = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) {
            AuthenticatedUser user = new AuthenticatedUser("user-" + i, "u" + i + "@example.com", "User " + i);
            calls.add(() -> {
                go.await();
                try {
                    return service.pick(user, seat(0, 3, 1));
                } catch (HoldConflictException e) {
                    return e;
                }
            });
        }
        List<Object> results = runTogether(calls, go);

        assertThat(results.stream().filter(r -> r instanceof HoldService.PickResult)).hasSize(1);
        assertThat(results.stream().filter(r -> r instanceof HoldConflictException)).hasSize(7);
        assertThat(rows("seat_reservation WHERE seat_id = 1")).isEqualTo(3);
        assertThat(rows("booking")).isEqualTo(1); // the seven that lost created no booking
    }

    @Test
    void manyUsersTakingTheLastStandingPlace_exactlyOneSucceeds_andTheCounterNeverExceedsTheCapacity() throws Exception {
        for (int segment = 0; segment < 3; segment++) {
            useStanding(TRIP, 30, segment, 4);
        }
        List<Callable<Object>> calls = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) {
            AuthenticatedUser user = new AuthenticatedUser("user-" + i, "u" + i + "@example.com", "User " + i);
            calls.add(() -> {
                go.await();
                try {
                    return service.pick(user, standing(0, 3, 30, 1));
                } catch (HoldConflictException e) {
                    return e;
                }
            });
        }
        List<Object> results = runTogether(calls, go);

        assertThat(results.stream().filter(r -> r instanceof HoldService.PickResult)).hasSize(1);
        for (int segment = 0; segment < 3; segment++) {
            assertThat(usedStanding(30, segment)).isEqualTo(5);
        }
    }

    @Test
    void theSameUserPickingFromTwoTabsAtTheSameTime_endsWithOneHold() throws Exception {
        List<Callable<Object>> calls = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (long seatId : new long[] {1, 2, 3, 4}) {
            calls.add(() -> {
                go.await();
                return service.pick(ALICE, seat(0, 3, seatId));
            });
        }
        runTogether(calls, go);

        assertThat(rows("booking WHERE user_id = 'alice-sub'")).isEqualTo(1); // one hold, every pick joined it
        assertThat(rows("booking_item")).isEqualTo(4);
        assertThat(rows("seat_reservation")).isEqualTo(12);
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
    void everyPickAndReleaseAnnouncesWhatChanged_afterTheCommit_eachEventWithItsOwnVersion() {
        BookingView hold = service.pick(ALICE, seat(0, 3, 1)).booking();
        service.pick(ALICE, standing(0, 3, 30, 2));
        service.release(ALICE, hold.id(), new ReleaseRequest(null, 30L, 1));

        assertThat(committed.seatEvents()).extracting(SeatEvent::type, SeatEvent::version).containsExactly(
                org.assertj.core.groups.Tuple.tuple(SeatEvent.SEAT_HELD, 1L), org.assertj.core.groups.Tuple.tuple(SeatEvent.STANDING_CHANGED, 2L),
                org.assertj.core.groups.Tuple.tuple(SeatEvent.STANDING_CHANGED, 3L));
        assertThat(committed.seatEvents().get(0).segments()).containsExactly(0, 1, 2);
        assertThat(committed.seatEvents().get(1).delta()).isEqualTo(-2);
        assertThat(committed.seatEvents().get(2).delta()).isEqualTo(1); // one place given back
        assertThat(version()).isEqualTo(3);
    }

    @Test
    void everyChangeIsWrittenToTheAuditLog_withTheUser() {
        BookingView hold = holds.hold(ALICE, seats(0, 1, 1L, 2L));
        service.release(ALICE, hold.id(), new ReleaseRequest(1L, null, null));

        assertThat(jdbc.sql("SELECT admin_id || ':' || action FROM audit_log ORDER BY id").query(String.class).list())
                .containsExactly("alice-sub:HOLD", "alice-sub:PICK", "alice-sub:RELEASE");
    }

    // ---- what is refused before anything is held ---------------------------------------------------------------------------

    @Test
    void aTripThatIsBeingCancelledOrIsCancelled_takesNoPicks() {
        jdbc.sql("UPDATE trip SET status = 'CANCELLING' WHERE id = :t").param("t", TRIP).update();

        assertThatThrownBy(() -> service.pick(ALICE, seat(0, 1, 1))).isInstanceOfSatisfying(TripClosedException.class, e -> assertThat(e.code()).isEqualTo("trip_closed"));
        assertThat(rows("seat_reservation")).isZero();
    }

    @Test
    void aTrainThatHasLeftTheBoardingStop_cannotBeBoarded_butALaterStopCan_andExactlyNowIsTooLate() {
        clock.set(Instant.parse("2026-11-02T06:30:00Z")); // 08:30 in Cairo: left stop 0 at 08:00, leaves stop 1 at 09:00

        assertThatThrownBy(() -> service.pick(ALICE, seat(0, 3, 1))).isInstanceOfSatisfying(TripClosedException.class, e -> assertThat(e.code()).isEqualTo("trip_departed"));
        assertThat(service.pick(ALICE, seat(1, 3, 1)).booking().status()).isEqualTo(BookingStatus.PENDING_PAYMENT);

        clock.set(Instant.parse("2026-11-02T06:00:00Z")); // exactly 08:00
        assertThatThrownBy(() -> service.pick(BOB, seat(0, 3, 2))).isInstanceOf(TripClosedException.class);
        clock.set(Instant.parse("2026-11-02T05:59:59Z"));
        assertThat(service.pick(BOB, seat(0, 3, 2)).booking().status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void anUnknownTrip_isNotFound_andAJourneyBeyondTheStopsIsRefused() {
        assertThatThrownBy(() -> service.pick(ALICE, new PickRequest(424242L, 0, 1, 1L, null, null))).isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> service.pick(ALICE, seat(0, 4, 1))).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> service.pick(ALICE, seat(2, 2, 1))).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void badPicks_areRefusedNamingTheField_andNothingIsHeld() {
        assertThat(problemsOf(new PickRequest(TRIP, 0, 1, null, null, null))).containsExactly("seatId"); // neither
        assertThat(problemsOf(new PickRequest(TRIP, 0, 1, 1L, 30L, 1))).containsExactly("seatId"); // both
        assertThat(problemsOf(seat(0, 1, 999))).containsExactly("seatId"); // a seat that is not on this trip
        assertThat(problemsOf(standing(0, 1, 10, 1))).containsExactly("coachId"); // FIRST coach: no standing places
        assertThat(problemsOf(standing(0, 1, 999, 1))).containsExactly("coachId");
        assertThat(rows("booking")).isZero();
    }

    private List<String> problemsOf(PickRequest request) {
        try {
            service.pick(ALICE, request);
            throw new AssertionError("the pick should have been refused");
        } catch (RequestValidationException e) {
            return e.details().stream().map(ApiError.Detail::field).toList();
        }
    }

    @Test
    void aTokenWithoutAnEmailAddress_cannotPick_andAVeryLongNameIsCut() {
        assertThatThrownBy(() -> service.pick(new AuthenticatedUser("nomail-sub", null, "No Mail"), seat(0, 1, 1)))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> assertThat(e.details()).extracting(ApiError.Detail::field).containsExactly("email"));

        BookingView booking = service.pick(new AuthenticatedUser("long-sub", "long@example.com", "N".repeat(300)), seat(0, 1, 1)).booking();
        assertThat(jdbc.sql("SELECT LENGTH(user_name) FROM booking WHERE id = :id").param("id", booking.id()).query(Integer.class).single()).isEqualTo(200);
    }

    // ---- who can read a booking ---------------------------------------------------------------------------------------------

    @Test
    void aBookingCanBeReadOnlyByItsOwner_forAnyoneElseItDoesNotExist_andTheActiveHoldIsFoundByUserAndTrip() {
        BookingView booking = service.pick(ALICE, seat(0, 1, 1)).booking();

        assertThat(queries.viewOwn(booking.id(), "alice-sub").reference()).isEqualTo(booking.reference());
        assertThatThrownBy(() -> queries.viewOwn(booking.id(), "bob-sub")).isInstanceOf(BookingNotFoundException.class);
        assertThat(queries.activeHold("alice-sub", TRIP)).get().extracting(BookingView::id).isEqualTo(booking.id());
        assertThat(queries.activeHold("bob-sub", TRIP)).isEmpty();
    }

    @Test
    void aBookingKeepsWhatThePassengerWasShown_evenIfALaterSnapshotChangesTheSeatsStopsAndCoaches() {
        BookingView booking = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of(new HoldRequest.StandingRequest(30L, 1))));
        jdbc.sql("UPDATE booking SET status = 'EXPIRED' WHERE id = :id").param("id", booking.id()).update();
        jdbc.sql("DELETE FROM seat_reservation").update();
        jdbc.sql("DELETE FROM trip_stop WHERE trip_id = :t AND stop_index = 3").param("t", TRIP).update();
        jdbc.sql("UPDATE trip_coach SET code = 'ZZ' WHERE trip_id = :t").param("t", TRIP).update();
        jdbc.sql("DELETE FROM trip_seat WHERE trip_id = :t AND seat_id = 2").param("t", TRIP).update();

        BookingView view = queries.viewOwn(booking.id(), "alice-sub");

        assertThat(view.items()).extracting(item -> item.type() + ":" + item.seatNumber() + ":" + item.coachCode() + ":" + item.coachClass())
                .containsExactly("SEAT:B1:B:SECOND", "STANDING:null:C:THIRD");
        assertThat(view.trip().to().stationName()).isEqualTo("?");
    }

    // ---- an open payment is cancelled before the hold changes (SRS 4.2) -------------------------------------------------------

    private BookingView holdWithPaymentStarted() {
        BookingView hold = holds.hold(ALICE, seats(0, 3, 1L, 2L));
        jdbc.sql("UPDATE booking SET payment_started = 1 WHERE id = :id").param("id", hold.id()).update();
        return hold;
    }

    @Test
    void aPickAfterTheUserWasAtThePaymentPage_firstInvalidatesTheOpenPayment_outsideAnyTransaction() {
        BookingView hold = holdWithPaymentStarted();
        List<String> seen = new ArrayList<>();
        payment.invalidateAnswers(id -> {
            seen.add("tx=" + org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    + ",seat3Held=" + rows("seat_reservation WHERE seat_id = 3"));
            return CancelOutcome.CANCELLED;
        });

        BookingView after = service.pick(ALICE, seat(0, 3, 3)).booking();

        assertThat(seen).containsExactly("tx=false,seat3Held=0"); // asked first, and not inside a transaction
        assertThat(payment.invalidateCalls()).containsExactly(hold.id());
        assertThat(payment.cancelCalls()).isEmpty();
        assertThat(after.items()).hasSize(3);
        assertThat(jdbc.sql("SELECT payment_started FROM booking WHERE id = :id").param("id", hold.id()).query(Integer.class).single()).isEqualTo(1); // never cleared: later changes keep asking Payment (cheap when nothing is open)
    }

    @Test
    void aPickWithoutAPaymentStarted_doesNotTouchPayment() {
        service.pick(ALICE, seat(0, 3, 1));
        service.pick(ALICE, seat(0, 3, 2));

        assertThat(payment.invalidateCalls()).isEmpty();
        assertThat(payment.cancelCalls()).isEmpty();
    }

    @Test
    void ifThePaymentAlreadySucceeded_thePickIsRefused_andTheHoldStaysForPaymentsReportToConfirm() {
        BookingView hold = holdWithPaymentStarted();
        payment.invalidateAnswers(id -> CancelOutcome.SUCCEEDED);

        assertThatThrownBy(() -> service.pick(ALICE, seat(0, 3, 3)))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("already_paid"));

        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT"); // Payment's report (with the amount) confirms it, not this pick
        assertThat(rows("seat_reservation WHERE seat_id = 3")).isZero();
        assertThat(rows("seat_reservation WHERE booking_id = " + hold.id())).isEqualTo(6); // nothing was released
    }

    @Test
    void ifThePaymentIsBeingProcessed_nothingChanges_forNow() {
        BookingView hold = holdWithPaymentStarted();
        payment.invalidateAnswers(id -> CancelOutcome.PROCESSING);

        assertThatThrownBy(() -> service.release(ALICE, hold.id(), new ReleaseRequest(1L, null, null)))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("payment_in_progress"));

        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(rows("seat_reservation WHERE seat_id = 1")).isEqualTo(3);
    }

    @Test
    void ifPaymentIsDown_theHoldDoesNotChange() {
        BookingView hold = holdWithPaymentStarted();
        payment.invalidateAnswers(id -> { throw new PaymentUnavailableException("down", new RuntimeException()); });

        assertThatThrownBy(() -> service.pick(ALICE, seat(0, 3, 3))).isInstanceOf(PaymentUnavailableException.class);

        assertThat(rows("booking_item WHERE booking_id = " + hold.id())).isEqualTo(2);
    }

    @Test
    void endingTheHoldAfterThePaymentPage_cancelsThePaymentForGood_first() {
        BookingView hold = holdWithPaymentStarted();
        List<String> seen = new ArrayList<>();
        payment.answers(id -> {
            seen.add("reservations=" + rows("seat_reservation") + ",status=" + statusOf(id));
            return CancelOutcome.CANCELLED;
        });

        BookingView after = service.releaseAll(ALICE, hold.id());

        assertThat(seen).containsExactly("reservations=6,status=PENDING_PAYMENT"); // cancelled while everything was still held
        assertThat(after.status()).isEqualTo(BookingStatus.ABANDONED);
        assertThat(payment.invalidateCalls()).isEmpty();
    }

    @Test
    void releasingTheLastSeatAfterThePaymentPage_alsoClosesThePayment_notJustInvalidatesIt() {
        BookingView hold = service.pick(ALICE, seat(0, 3, 1)).booking();
        jdbc.sql("UPDATE booking SET payment_started = 1 WHERE id = :id").param("id", hold.id()).update();

        BookingView after = service.release(ALICE, hold.id(), new ReleaseRequest(1L, null, null));

        assertThat(after.status()).isEqualTo(BookingStatus.ABANDONED);
        assertThat(payment.cancelCalls()).containsExactly(hold.id());
        assertThat(payment.invalidateCalls()).isEmpty();
    }
}
