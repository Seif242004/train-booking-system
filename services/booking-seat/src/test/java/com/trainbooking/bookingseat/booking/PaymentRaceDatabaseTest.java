package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.PickRequest;
import com.trainbooking.bookingseat.booking.model.ReleaseRequest;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.payment.PaymentPort.CancelOutcome;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The user opens the payment page at the very moment the hold is changed (another tab). The change looked at the booking before the
 * payment page was opened, so it did not ask Payment to cancel anything; it must notice under the lock that a payment may now exist,
 * change nothing, cancel that payment first and then go on. Never may a seat be released or the amount changed with a payment open.
 */
class PaymentRaceDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9001;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice");

    @Autowired
    HoldService service;
    @Autowired
    Holds holds;
    @Autowired
    com.trainbooking.bookingseat.booking.service.ExpiryService expiry;
    @MockitoSpyBean
    BookingRepository bookings;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    @AfterEach
    void realRepository() {
        reset(bookings);
    }

    private static PickRequest seat(long seatId) {
        return new PickRequest(TRIP, 0, 3, seatId, null, null);
    }

    /** A hold whose payment page was opened, and a copy of the row as it looked before that (what a slow reader saw). */
    private Booking holdWithPaymentOpened() {
        BookingView hold = holds.hold(ALICE, new com.trainbooking.bookingseat.booking.model.HoldRequest(TRIP, 0, 3, java.util.List.of(2L, 3L), java.util.List.of()));
        Booking before = bookings.find(hold.id()).orElseThrow();
        jdbc.sql("UPDATE booking SET payment_started = 1 WHERE id = :id").param("id", hold.id()).update();
        return before; // paymentStarted is false in this copy
    }

    @Test
    void aPickThatLookedBeforeThePaymentPageOpened_cancelsThePaymentFirst_thenPicks() {
        Booking stale = holdWithPaymentOpened();
        doReturn(Optional.of(stale)).doCallRealMethod().when(bookings).findActiveHold(anyString(), anyLong());
        payment.alwaysInvalidateAnswers(CancelOutcome.CANCELLED);

        BookingView after = service.pick(ALICE, seat(1)).booking();

        assertThat(payment.invalidateCalls()).containsExactly(stale.id()); // the second attempt asked Payment before changing anything
        assertThat(after.items()).hasSize(3);
        assertThat(rows("seat_reservation WHERE booking_id = " + stale.id())).isEqualTo(9); // 3 seats x 3 segments
    }

    @Test
    void aReleaseThatLookedBeforeThePaymentPageOpened_doesNotReleaseUntilThePaymentIsCancelled() {
        Booking stale = holdWithPaymentOpened();
        doReturn(Optional.of(stale)).doCallRealMethod().when(bookings).find(stale.id());
        payment.alwaysInvalidateAnswers(CancelOutcome.CANCELLED);

        BookingView after = service.release(ALICE, stale.id(), new ReleaseRequest(2L, null, null));

        assertThat(payment.invalidateCalls()).containsExactly(stale.id());
        assertThat(after.items()).hasSize(1);
    }

    @Test
    void releasingEverything_thatLookedBeforeThePaymentPageOpened_cancelsThePaymentBeforeTheSeatsGoBack() {
        Booking stale = holdWithPaymentOpened();
        doReturn(Optional.of(stale)).doCallRealMethod().when(bookings).find(stale.id());
        java.util.List<Integer> seatsHeldWhenPaymentWasCancelled = new java.util.ArrayList<>();
        payment.answers(id -> {
            seatsHeldWhenPaymentWasCancelled.add(rows("seat_reservation WHERE booking_id = " + id));
            return CancelOutcome.CANCELLED;
        });

        service.releaseAll(ALICE, stale.id());

        assertThat(payment.cancelCalls()).containsExactly(stale.id());
        assertThat(seatsHeldWhenPaymentWasCancelled).containsExactly(6); // cancelled while the seats were still held
        assertThat(rows("seat_reservation WHERE booking_id = " + stale.id())).isZero();
    }

    @Test
    void theSweeper_thatLookedBeforeThePaymentPageOpened_asksPaymentAtTheNextSweepInstead_andReleasesNothingMeanwhile() {
        Booking stale = holdWithPaymentOpened(); // the row says payment_started = 1 now; the sweeper's first look sees 0
        doReturn(Optional.of(stale)).doCallRealMethod().when(bookings).find(stale.id());
        clock.advance(java.time.Duration.ofMinutes(5).plusSeconds(1));
        payment.alwaysAnswers(CancelOutcome.CANCELLED);

        expiry.sweep();

        assertThat(payment.cancelCalls()).isEmpty();                                 // it did not know, so it did not ask
        assertThat(rows("seat_reservation WHERE booking_id = " + stale.id())).isEqualTo(6); // and it released nothing: refused under the lock
        clock.advance(java.time.Duration.ofMillis(1100));
        expiry.sweep();
        assertThat(payment.cancelCalls()).containsExactly(stale.id());               // the next sweep cancels the payment first
        assertThat(rows("seat_reservation WHERE booking_id = " + stale.id())).isZero();
    }

    @Test
    void ifTheLookKeepsBeingStale_thePickIsRefusedAndNothingChanges() {
        Booking stale = holdWithPaymentOpened();
        doReturn(Optional.of(stale)).when(bookings).findActiveHold(anyString(), anyLong()); // always the old copy

        assertThatThrownBy(() -> service.pick(ALICE, seat(1)))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("payment_in_progress");

        assertThat(rows("booking_item WHERE booking_id = " + stale.id())).isEqualTo(2);
        assertThat(rows("seat_reservation WHERE seat_id = 1")).isZero();
    }
}
