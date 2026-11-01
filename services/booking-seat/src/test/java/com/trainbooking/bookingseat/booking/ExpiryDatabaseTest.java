package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldConflictException;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.service.ExpiryService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.live.SeatEvent;
import com.trainbooking.bookingseat.live.UserEvent;
import com.trainbooking.bookingseat.payment.PaymentPort.CancelOutcome;
import com.trainbooking.bookingseat.payment.PaymentUnavailableException;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The expiry of holds (Flow 5, SRS 4.2, FR-H5, FR-P4, acceptance 4 and 5): the pending payment is cancelled FIRST, outside any
 * transaction, and only then are the seats released; a payment that won the race confirms the booking instead; a payment that is
 * still processing, or a Stripe that is down, keeps the seats held until the answer is known.
 */
class ExpiryDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9101;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice");
    private static final AuthenticatedUser BOB = new AuthenticatedUser("bob-sub", "bob@example.com", "Bob");

    @Autowired
    HoldService holds;

    @Autowired
    ExpiryService expiry;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    private BookingView holdSeatAndStanding() {
        return holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of(new HoldRequest.StandingRequest(30L, 2))));
    }

    private String statusOf(long bookingId) {
        return jdbc.sql("SELECT status FROM booking WHERE id = :id").param("id", bookingId).query(String.class).single();
    }

    private int usedStanding(int segment) {
        return jdbc.sql("SELECT used FROM standing_usage WHERE trip_id = :t AND coach_id = 30 AND segment_index = :s").param("t", TRIP).param("s", segment).query(Integer.class).single();
    }

    private void pastExpiry() {
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
    }

    // ---- the normal expiry ----------------------------------------------------------------------------------------------

    @Test
    void anExpiredHold_isReleased_andTheSeatsAndStandingPlacesAreGivenBack() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();

        expiry.sweep();

        assertThat(statusOf(booking.id())).isEqualTo("EXPIRED");
        assertThat(rows("seat_reservation")).isZero();
        assertThat(rows("standing_hold")).isZero();
        assertThat(usedStanding(0) + usedStanding(1) + usedStanding(2)).isZero();
        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE entity = 'booking' ORDER BY id").query(String.class).list()).containsExactly("HOLD", "EXPIRE");
    }

    @Test
    void theSeatIsFreeAgainForAnotherUser_assoonAsTheSweeperReleasedIt() {
        holdSeatAndStanding();
        pastExpiry();
        expiry.sweep();

        assertThat(holds.hold(BOB, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of())).status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void theReleaseIsAnnounced_afterTheCommit_toEveryoneAndToTheUser() {
        BookingView booking = holdSeatAndStanding();
        committed.clear();
        pastExpiry();

        expiry.sweep();

        assertThat(committed.seatEvents()).extracting(SeatEvent::type).containsExactly(SeatEvent.SEAT_RELEASED, SeatEvent.STANDING_CHANGED);
        assertThat(committed.seatEvents().get(1).delta()).isEqualTo(2); // the places come back
        assertThat(committed.userEvents()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(UserEvent.HOLD_EXPIRED);
            assertThat(event.userId()).isEqualTo("alice-sub");
            assertThat(event.bookingId()).isEqualTo(booking.id());
            assertThat(event.reason()).isEqualTo("EXPIRED");
        });
        assertThat(committed.seatEvents().get(0).version()).isLessThan(committed.seatEvents().get(1).version());
    }

    @Test
    void aHoldThatHasNotRunOut_isLeftAlone_andOneThatJustRanOutIsNot() {
        BookingView booking = holdSeatAndStanding();

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        expiry.sweep();
        assertThat(statusOf(booking.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(payment.cancelCalls()).isEmpty();

        clock.advance(Duration.ofSeconds(1)); // exactly five minutes
        expiry.sweep();
        assertThat(statusOf(booking.id())).isEqualTo("EXPIRED");
    }

    // ---- cancel Stripe first ----------------------------------------------------------------------------------------------

    @Test
    void thePendingPaymentIsCancelledBeforeAnythingIsReleased_andOutsideAnyTransaction() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();
        List<String> seenAtTheTimeOfTheCall = new ArrayList<>();
        payment.answers(id -> {
            // The state of the database and of the thread at the very moment Payment is asked to cancel:
            seenAtTheTimeOfTheCall.add("reservations=" + rows("seat_reservation") + ",status=" + statusOf(id) + ",tx=" + TransactionSynchronizationManager.isActualTransactionActive());
            return CancelOutcome.CANCELLED;
        });

        expiry.sweep();

        assertThat(seenAtTheTimeOfTheCall).containsExactly("reservations=3,status=PENDING_PAYMENT,tx=false");
        assertThat(payment.cancelCalls()).containsExactly(booking.id());
        assertThat(statusOf(booking.id())).isEqualTo("EXPIRED"); // released only afterwards
    }

    @Test
    void ifStripeRefusesBecauseThePaymentAlreadySucceeded_thePaymentWins_theBookingIsConfirmed_andTheSeatsAreKept() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();
        payment.alwaysAnswers(CancelOutcome.SUCCEEDED);
        committed.clear();

        expiry.sweep();

        assertThat(statusOf(booking.id())).isEqualTo("CONFIRMED");
        assertThat(rows("seat_reservation WHERE booking_id = " + booking.id())).isEqualTo(3);
        assertThat(rows("seat_reservation WHERE expires_at IS NOT NULL")).isZero(); // no longer a hold: the sweeper can never take it
        assertThat(usedStanding(1)).isEqualTo(2); // the standing places stay taken
        assertThat(committed.seatEvents()).extracting(SeatEvent::type).containsExactly(SeatEvent.SEAT_BOOKED);
        assertThat(committed.userEvents()).isEmpty(); // nobody is sent back to the seat map
    }

    @Test
    void aConfirmedBookingIsNeverReleasedByLaterSweeps() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();
        payment.alwaysAnswers(CancelOutcome.SUCCEEDED);
        expiry.sweep();
        payment.reset();

        clock.advance(Duration.ofHours(1));
        expiry.sweep();

        assertThat(statusOf(booking.id())).isEqualTo("CONFIRMED");
        assertThat(payment.cancelCalls()).isEmpty(); // Payment was not asked again (the list was cleared after the first sweep)
    }

    @Test
    void aPaymentThatIsStillProcessing_keepsTheSeatsHeld_andIsAskedAgainAtTheNextSweep() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();
        payment.alwaysAnswers(CancelOutcome.PROCESSING);

        expiry.sweep();
        assertThat(statusOf(booking.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(rows("seat_reservation")).isEqualTo(3);
        assertThat(payment.cancelCalls()).hasSize(1);

        expiry.sweep(); // the same second: not asked again yet
        assertThat(payment.cancelCalls()).hasSize(1);

        clock.advance(Duration.ofMillis(1100));
        payment.alwaysAnswers(CancelOutcome.PROCESSING);
        expiry.sweep();
        assertThat(payment.cancelCalls()).hasSize(2); // asked again at the next sweep after its second

        clock.advance(Duration.ofMillis(1100));
        payment.alwaysAnswers(CancelOutcome.CANCELLED); // Stripe settled: the payment failed or was cancelled
        expiry.sweep();
        assertThat(statusOf(booking.id())).isEqualTo("EXPIRED");
        assertThat(rows("seat_reservation")).isZero();
    }

    @Test
    void aPaymentThatSucceedsWhileItWasProcessing_confirmsTheBooking() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();
        payment.alwaysAnswers(CancelOutcome.PROCESSING);
        expiry.sweep();

        clock.advance(Duration.ofMillis(1100));
        payment.alwaysAnswers(CancelOutcome.SUCCEEDED);
        expiry.sweep();

        assertThat(statusOf(booking.id())).isEqualTo("CONFIRMED");
        assertThat(rows("seat_reservation WHERE expires_at IS NULL")).isEqualTo(3);
    }

    @Test
    void whenStripeOrPaymentIsDown_theSeatsStayHeld_andTheRetriesBackOff_insteadOfHammeringEverySecond() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();
        payment.answers(id -> { throw new PaymentUnavailableException("Payment is down", new RuntimeException()); });

        expiry.sweep(); // attempt 1
        assertThat(statusOf(booking.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(rows("seat_reservation")).isEqualTo(3);
        expiry.sweep(); // a second later: not due yet
        assertThat(payment.cancelCalls()).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        expiry.sweep(); // still inside the first backoff
        assertThat(payment.cancelCalls()).hasSize(1);

        clock.advance(Duration.ofSeconds(1)); // the first backoff (2 s) is over
        expiry.sweep(); // attempt 2, next delay 4 s
        assertThat(payment.cancelCalls()).hasSize(2);
        clock.advance(Duration.ofSeconds(3));
        expiry.sweep();
        assertThat(payment.cancelCalls()).hasSize(2);

        payment.alwaysAnswers(CancelOutcome.CANCELLED); // Payment is back
        clock.advance(Duration.ofSeconds(2));
        expiry.sweep();
        assertThat(statusOf(booking.id())).isEqualTo("EXPIRED");
        assertThat(rows("seat_reservation")).isZero();
    }

    @Test
    void aBookingThatIsConfirmedWhileThePaymentIsBeingCancelled_isNotReleased() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();
        payment.answers(id -> {
            // The webhook wins the race: between asking Payment and releasing, the booking is confirmed.
            jdbc.sql("UPDATE booking SET status = 'CONFIRMED' WHERE id = :id").param("id", id).update();
            return CancelOutcome.CANCELLED;
        });

        expiry.sweep();

        assertThat(statusOf(booking.id())).isEqualTo("CONFIRMED");
        assertThat(rows("seat_reservation")).isEqualTo(3); // the seats of a paid booking are never released
    }

    @Test
    void oneBookingThatFails_doesNotStopTheOthers() {
        BookingView first = holdSeatAndStanding();
        BookingView second = holds.hold(BOB, new HoldRequest(TRIP, 0, 3, List.of(3L), List.of()));
        pastExpiry();
        payment.answers(id -> {
            if (id == first.id()) {
                throw new IllegalStateException("something unexpected");
            }
            return CancelOutcome.CANCELLED;
        });

        expiry.sweep();

        assertThat(statusOf(first.id())).isEqualTo("PENDING_PAYMENT"); // kept, tried again later
        assertThat(statusOf(second.id())).isEqualTo("EXPIRED");
    }

    @Test
    void aHoldThatIsAlreadyOver_isNeverGivenToPaymentAgain() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();
        expiry.sweep();
        payment.reset();

        expiry.sweep();

        assertThat(payment.cancelCalls()).isEmpty();
        assertThat(statusOf(booking.id())).isEqualTo("EXPIRED");
    }

    @Test
    void aTimedOutHoldIsNeverCharged_theSweeperAsksOnlyToCancel_neverToRefund() {
        // The port has no refund operation at all: expiry can only cancel a pending payment, so a timeout cannot refund (FR-P4).
        assertThat(com.trainbooking.bookingseat.payment.PaymentPort.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName).containsExactly("cancelPending");
    }

    @Test
    void afterTheRelease_theSameUserCanHoldAgain_andTheOldBookingStaysAsHistory() {
        BookingView old = holdSeatAndStanding();
        pastExpiry();
        expiry.sweep();

        BookingView again = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of()));

        assertThat(again.id()).isNotEqualTo(old.id());
        assertThat(rows("booking")).isEqualTo(2);
        try {
            holds.hold(BOB, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of()));
            throw new AssertionError("seat 2 is held by Alice again");
        } catch (HoldConflictException expected) {
            // fine
        }
    }

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transaction;

    @Test
    void expiringAHoldInsideATransaction_isRefused_becauseItCallsPaymentAndStripeFirst() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.executeWithoutResult(status -> expiry.expire(booking.id())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside any transaction");

        assertThat(payment.cancelCalls()).isEmpty(); // Payment was never called from inside the transaction
        assertThat(statusOf(booking.id())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void ifPaymentReportsASuccessForAHoldThatIsAlreadyGone_nothingIsConfirmed_andTheSweeperGoesOn() {
        BookingView booking = holdSeatAndStanding();
        pastExpiry();
        payment.answers(id -> {
            jdbc.sql("UPDATE booking SET status = 'EXPIRED' WHERE id = :id").param("id", id).update(); // released by someone else meanwhile
            return CancelOutcome.SUCCEEDED;
        });

        expiry.sweep();

        assertThat(statusOf(booking.id())).isEqualTo("EXPIRED"); // confirm() found it not confirmable and changed nothing
    }
}
