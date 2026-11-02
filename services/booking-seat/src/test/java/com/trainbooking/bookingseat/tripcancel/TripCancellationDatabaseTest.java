package com.trainbooking.bookingseat.tripcancel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.PickRequest;
import com.trainbooking.bookingseat.booking.model.TripClosedException;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import com.trainbooking.bookingseat.booking.service.ConfirmationService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.booking.service.PaymentResultService;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import com.trainbooking.bookingseat.live.SeatEvent;
import com.trainbooking.bookingseat.live.UserEvent;
import com.trainbooking.bookingseat.outbox.service.OutboxDispatcher;
import com.trainbooking.bookingseat.payment.PaymentPort.CancelOutcome;
import com.trainbooking.bookingseat.payment.PaymentUnavailableException;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
import com.trainbooking.bookingseat.tripcancel.model.TripCancellationStatus;
import com.trainbooking.bookingseat.tripcancel.service.TripCancellationService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * An admin cancels a whole trip (Flow 8, SRS 7.3, FR-C8): the trip closes to new holds, Train &amp; Schedule is told, active holds are released
 * (their payment cancelled first) and sent back with "This trip was cancelled", every confirmed booking is cancelled and refunded as in Flow 6,
 * and what fails is retried: the trip is "bookings pending" (CANCELLING) until nothing is left.
 */
class TripCancellationDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9701;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice");
    private static final AuthenticatedUser BOB = new AuthenticatedUser("bob-sub", "bob@example.com", "Bob");
    private static final AuthenticatedUser CAROL = new AuthenticatedUser("carol-sub", "carol@example.com", "Carol");
    private static final Instant DEPARTURE = Instant.parse("2026-11-02T06:00:00Z");

    @Autowired
    Holds holds;
    @Autowired
    HoldService holdService;
    @Autowired
    ConfirmationService confirmations;
    @Autowired
    TripCancellationService tripCancellations;
    @Autowired
    CancellationService cancellations;
    @Autowired
    PaymentResultService results;
    @Autowired
    OutboxDispatcher dispatcher;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    private BookingView confirmed(AuthenticatedUser user, long seat) {
        BookingView hold = holds.hold(user, new HoldRequest(TRIP, 0, 3, List.of(seat), List.of()));
        confirmations.confirm(hold.id());
        return hold;
    }

    private BookingView aHold(AuthenticatedUser user, long seat) {
        return holds.hold(user, new HoldRequest(TRIP, 0, 3, List.of(seat), List.of()));
    }

    private void paymentPageOpened(long bookingId) {
        jdbc.sql("UPDATE booking SET payment_started = 1 WHERE id = :id").param("id", bookingId).update();
    }

    private String statusOf(long id) {
        return jdbc.sql("SELECT status FROM booking WHERE id = :id").param("id", id).query(String.class).single();
    }

    private String tripStatus() {
        return jdbc.sql("SELECT status FROM trip WHERE id = :t").param("t", TRIP).query(String.class).single();
    }

    private int drain() {
        int total = 0;
        for (int round = 0; round < 12; round++) {
            int done = dispatcher.dispatchDue();
            if (done == 0) {
                break;
            }
            total += done;
        }
        return total;
    }

    // ---- the whole cancellation ---------------------------------------------------------------------------------------

    @Test
    void theWholeTrip_everythingIsCancelled_holdsAreSentBack_everyBookingIsRefunded_andEveryoneViewingIsTold() {
        BookingView alice = confirmed(ALICE, 2);
        BookingView bob = confirmed(BOB, 3);
        BookingView holdWithPayment = aHold(CAROL, 1);
        paymentPageOpened(holdWithPayment.id());
        payment.alwaysAnswers(CancelOutcome.CANCELLED);
        committed.clear();
        drain(); // the ticket emails of the two confirmations go out first, as in the real service
        committed.clear();

        TripCancellationStatus status = tripCancellations.request("admin-sub", TRIP, "Strike");

        assertThat(status.status()).isEqualTo("CANCELLED");
        assertThat(status.trainScheduleNotified()).isTrue();
        assertThat(status.pendingHolds()).isZero();
        assertThat(status.pendingConfirmed()).isZero();
        assertThat(status.cancelledBookings()).isEqualTo(2);
        assertThat(tripStatus()).isEqualTo("CANCELLED");
        assertThat(trainSchedule.calls()).containsExactly(TRIP + ":admin-sub");

        // The hold: its payment was cancelled FIRST, then it was released as EXPIRED, and its user is sent back with the trip message.
        assertThat(payment.cancelCalls()).containsExactly(holdWithPayment.id());
        assertThat(statusOf(holdWithPayment.id())).isEqualTo("EXPIRED");
        assertThat(committed.userEvents()).singleElement().satisfies(event -> {
            assertThat(event.userId()).isEqualTo("carol-sub");
            assertThat(event.reason()).isEqualTo("TRIP_CANCELLED");
        });

        // The confirmed bookings: cancelled by the admin, with the reason, exactly as a support cancellation.
        assertThat(statusOf(alice.id())).isEqualTo("CANCELLED");
        assertThat(statusOf(bob.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT cancel_reason FROM booking WHERE id = :id").param("id", alice.id()).query(String.class).single()).isEqualTo("The trip was cancelled: Strike");
        assertThat(jdbc.sql("SELECT cancelled_by || '/' || cancelled_by_role FROM booking WHERE id = :id").param("id", bob.id()).query(String.class).single()).isEqualTo("admin-sub/ADMIN");
        assertThat(rows("seat_reservation")).isZero();
        assertThat(committed.seatEvents()).extracting(SeatEvent::type).contains(SeatEvent.SEAT_RELEASED, SeatEvent.TRIP_CANCELLED);

        // The follow-up, from the outbox: tickets invalidated, a full refund asked for each, the passengers emailed.
        drain();
        assertThat(tickets.invalidated()).containsExactlyInAnyOrder(alice.id(), bob.id());
        assertThat(payment.refundCalls()).extracting(call -> call.split(":")[0]).containsExactlyInAnyOrder(String.valueOf(alice.id()), String.valueOf(bob.id()));
        assertThat(notifications.accepted()).extracting(email -> email.to() + "/" + email.kind()).contains("alice@example.com/CANCELLATION", "bob@example.com/CANCELLATION");
        assertThat(notifications.accepted().stream().filter(e -> e.to().startsWith("alice") && e.kind() == com.trainbooking.bookingseat.notification.NotificationPort.Kind.CANCELLATION).findFirst().orElseThrow().detail()).contains("The trip was cancelled: Strike");
    }

    @Test
    void theTripIsClosedToNewHolds_assoonAsItIsBeingCancelled() {
        confirmed(ALICE, 2);
        tripCancellations.request("admin-sub", TRIP, "Strike");

        assertThatThrownBy(() -> holdService.pick(BOB, new PickRequest(TRIP, 0, 3, 1L, null, null))).isInstanceOf(TripClosedException.class);
    }

    @Test
    void aTripThatHasLeftItsFirstStop_cannotBeCancelled_andNothingChanges() {
        BookingView alice = confirmed(ALICE, 2);
        clock.set(DEPARTURE.plusSeconds(1));

        assertThatThrownBy(() -> tripCancellations.request("admin-sub", TRIP, "Late"))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("trip_departed");

        assertThat(tripStatus()).isEqualTo("OPEN");
        assertThat(statusOf(alice.id())).isEqualTo("CONFIRMED");
        assertThat(trainSchedule.calls()).isEmpty();
    }

    @Test
    void aReasonOfTheFullLength_stillCancelsEveryBooking() {
        // The stored text is "The trip was cancelled: " + reason; booking.cancel_reason holds 500 characters, so the text is cut, not refused.
        BookingView alice = confirmed(ALICE, 2);

        TripCancellationStatus status = tripCancellations.request("admin-sub", TRIP, "x".repeat(500));

        assertThat(status.status()).isEqualTo("CANCELLED");
        assertThat(statusOf(alice.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT LENGTH(cancel_reason) FROM booking WHERE id = :id").param("id", alice.id()).query(Integer.class).single()).isEqualTo(500);
    }

    @Test
    void aPassAfterTheTrainHasLeft_stillCancelsAndRefundsTheConfirmedBookings() {
        // The trip was being cancelled before departure but a booking was left for a later pass; the boarding-stop cutoff must not strand it.
        BookingView alice = confirmed(ALICE, 2);
        jdbc.sql("UPDATE trip SET status = 'CANCELLING', cancel_reason = 'Strike', cancelled_by = 'admin-sub' WHERE id = :t").param("t", TRIP).update();
        clock.set(DEPARTURE.plusSeconds(60));

        TripCancellationStatus status = tripCancellations.process(TRIP);

        assertThat(statusOf(alice.id())).isEqualTo("CANCELLED");
        assertThat(status.status()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT refund_status FROM booking WHERE id = :id").param("id", alice.id()).query(String.class).single()).isEqualTo("REFUND_PENDING");
    }

    @Test
    void aPassengersOwnCancellation_afterDeparture_isStillRefused() {
        // Only the trip path skips the cutoff.
        BookingView alice = confirmed(ALICE, 2);
        clock.set(DEPARTURE.plusSeconds(60));

        assertThatThrownBy(() -> cancellations.cancel(CancellationService.Actor.user("alice-sub"), alice.id(), null))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("trip_departed");
    }

    @Test
    void anUnknownTrip_isNotFound() {
        assertThatThrownBy(() -> tripCancellations.request("admin-sub", 424242, "x")).isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> tripCancellations.status(424242)).isInstanceOf(TripNotFoundException.class);
    }

    // ---- retries ----------------------------------------------------------------------------------------------------

    @Test
    void whenTrainScheduleIsDown_theBookingsAreStillCancelled_theTripStaysPending_andTheRetryFinishesIt() {
        BookingView alice = confirmed(ALICE, 2);
        trainSchedule.down(true);

        TripCancellationStatus first = tripCancellations.request("admin-sub", TRIP, "Strike");

        assertThat(first.status()).isEqualTo("CANCELLING");               // shown as "CANCELLED - bookings pending"
        assertThat(first.trainScheduleNotified()).isFalse();
        assertThat(statusOf(alice.id())).isEqualTo("CANCELLED");          // a booking never waits for Train & Schedule
        assertThat(tripStatus()).isEqualTo("CANCELLING");

        trainSchedule.down(false);
        TripCancellationStatus retried = tripCancellations.process(TRIP);  // what the worker does every few seconds

        assertThat(retried.status()).isEqualTo("CANCELLED");
        assertThat(trainSchedule.calls()).containsExactly(TRIP + ":admin-sub");
    }

    @Test
    void whenPaymentIsDown_aHoldWithAPaymentPageStaysHeld_theTripStaysPending_andTheRetryReleasesIt() {
        BookingView hold = aHold(BOB, 3);
        paymentPageOpened(hold.id());
        payment.answers(id -> { throw new PaymentUnavailableException("Payment is down", null); });

        TripCancellationStatus first = tripCancellations.request("admin-sub", TRIP, "Strike");

        assertThat(first.status()).isEqualTo("CANCELLING");
        assertThat(first.pendingHolds()).isEqualTo(1);
        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT");     // never released while its payment could still be paid
        assertThat(rows("seat_reservation WHERE booking_id = " + hold.id())).isEqualTo(3);

        payment.reset();
        payment.alwaysAnswers(CancelOutcome.CANCELLED);
        assertThat(tripCancellations.process(TRIP).status()).isEqualTo("CANCELLED");
        assertThat(statusOf(hold.id())).isEqualTo("EXPIRED");
    }

    @Test
    void aPaymentThatSucceededAtTheLastSecond_isConfirmedByPaymentsReport_thenCancelledAndRefundedByTheNextPass() {
        BookingView hold = aHold(BOB, 3);
        paymentPageOpened(hold.id());
        payment.alwaysAnswers(CancelOutcome.SUCCEEDED);

        TripCancellationStatus first = tripCancellations.request("admin-sub", TRIP, "Strike");
        assertThat(first.status()).isEqualTo("CANCELLING");
        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT");     // the money is taken: the seat is kept for the moment

        results.paymentSucceeded(hold.id(), new BigDecimal(jdbc.sql("SELECT amount FROM booking WHERE id = :id").param("id", hold.id()).query(String.class).single()));
        TripCancellationStatus second = tripCancellations.process(TRIP);

        assertThat(statusOf(hold.id())).isEqualTo("CANCELLED");           // refunded like any confirmed booking
        assertThat(second.status()).isEqualTo("CANCELLED");
        drain();
        assertThat(payment.refundCalls()).hasSize(1);
    }

    @Test
    void askingAgain_isSafe_nothingIsCancelledOrRefundedTwice() {
        BookingView alice = confirmed(ALICE, 2);
        tripCancellations.request("admin-sub", TRIP, "Strike");
        committed.clear();

        TripCancellationStatus again = tripCancellations.request("another-admin", TRIP, "Different reason");

        assertThat(again.status()).isEqualTo("CANCELLED");
        assertThat(again.reason()).isEqualTo("Strike");                    // the first reason and admin stay on record
        assertThat(again.cancelledBy()).isEqualTo("admin-sub");
        assertThat(rows("outbox WHERE kind = 'REFUND_REQUEST' AND booking_id = " + alice.id())).isEqualTo(1);
        assertThat(trainSchedule.calls()).hasSize(1);
        assertThat(committed.seatEvents()).isEmpty();
    }

    @Test
    void theStatus_countsWhatIsLeft_andTheRefundsThatFailed() {
        BookingView alice = confirmed(ALICE, 2);
        trainSchedule.down(true);
        tripCancellations.request("admin-sub", TRIP, "Strike");
        results.refundStatus(alice.id(), com.trainbooking.bookingseat.booking.model.RefundStatus.REFUND_FAILED);

        TripCancellationStatus status = tripCancellations.status(TRIP);

        assertThat(status.status()).isEqualTo("CANCELLING");
        assertThat(status.cancelledBookings()).isEqualTo(1);
        assertThat(status.refundsFailed()).isEqualTo(1);
        assertThat(status.pendingConfirmed()).isZero();
    }

    @Test
    void aTripThatIsNotBeingCancelled_isOpen() {
        TripCancellationStatus status = tripCancellations.status(TRIP);

        assertThat(status.status()).isEqualTo("OPEN");
        assertThat(status.trainScheduleNotified()).isFalse();
    }

    @Test
    void otherTripsAreNotTouched() {
        long other = 9702;
        insertTrip(other);
        BookingView onOther = holds.hold(ALICE, new HoldRequest(other, 0, 3, List.of(2L), List.of()));
        confirmations.confirm(onOther.id());

        tripCancellations.request("admin-sub", TRIP, "Strike");

        assertThat(statusOf(onOther.id())).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT status FROM trip WHERE id = :t").param("t", other).query(String.class).single()).isEqualTo("OPEN");
        assertThat(Duration.ZERO).isNotNull();
    }
}
