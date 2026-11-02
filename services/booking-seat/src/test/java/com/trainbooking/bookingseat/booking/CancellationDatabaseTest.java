package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.bookingseat.availability.model.SeatState;
import com.trainbooking.bookingseat.availability.service.AvailabilityService;
import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.MyBooking;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.service.BookingQueryService;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import com.trainbooking.bookingseat.booking.service.CancellationService.Actor;
import com.trainbooking.bookingseat.booking.service.ConfirmationService;
import com.trainbooking.bookingseat.booking.service.PaymentResultService;
import com.trainbooking.bookingseat.live.SeatEvent;
import com.trainbooking.bookingseat.notification.NotificationPort;
import com.trainbooking.bookingseat.outbox.service.OutboxDispatcher;
import com.trainbooking.bookingseat.payment.PaymentUnavailableException;
import com.trainbooking.bookingseat.payment.RefundPort;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Cancellation of a confirmed booking (Flow 6, SRS 7.2, FR-C1 to FR-C5, FR-C7): one implementation for the passenger and the admin, instant,
 * until the train leaves the booking's boarding stop, with the follow-up (ticket, full refund, email) queued in the same transaction.
 * The trip leaves Cairo at 08:00 Cairo time (06:00 UTC) and each next stop an hour later; the clock starts the day before.
 */
class CancellationDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9601;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice Example");
    private static final Instant DEPARTURE = Instant.parse("2026-11-02T06:00:00Z");

    @Autowired
    Holds holds;
    @Autowired
    ConfirmationService confirmations;
    @Autowired
    CancellationService cancellations;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    PaymentResultService results;
    @Autowired
    BookingQueryService queries;
    @Autowired
    AvailabilityService availability;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    /** Seat 2 (SECOND) and two standing places in coach 30 (THIRD) for the whole trip, confirmed (as Payment's report does). */
    private BookingView confirmed(AuthenticatedUser user, int from, int to, long seat) {
        BookingView hold = holds.hold(user, new HoldRequest(TRIP, from, to, List.of(seat), List.of(new HoldRequest.StandingRequest(30L, 2))));
        confirmations.confirm(hold.id());
        drain(); // the ticket email of the confirmation goes out first, like in the real service
        committed.clear();
        tickets.reset();
        notifications.reset();
        return hold;
    }

    /**
     * Runs the outbox until nothing more is due. The jobs of one booking run in order, one per run (the real service runs every second), so
     * the three jobs of a cancellation need three runs.
     */
    private int drain() {
        int total = 0;
        for (int round = 0; round < 10; round++) {
            int done = dispatcher.dispatchDue();
            if (done == 0) {
                break;
            }
            total += done;
        }
        return total;
    }

    private String column(String name, long id) {
        return jdbc.sql("SELECT " + name + " FROM booking WHERE id = :id").param("id", id).query(String.class).single();
    }

    private int usedStanding() {
        return jdbc.sql("SELECT NVL(SUM(used), 0) FROM standing_usage WHERE trip_id = :t").param("t", TRIP).query(Integer.class).single();
    }

    // ---- the cancellation ------------------------------------------------------------------------------------------

    @Test
    void thePassenger_cancelsTheirOwnBooking_instantly_andTheSeatsAreFreeForEveryoneAtOnce() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);

        BookingView after = cancellations.cancel(Actor.user("alice-sub"), booking.id(), "ignored for a passenger");

        assertThat(after.status().name()).isEqualTo("CANCELLED");
        assertThat(after.refundStatus()).isEqualTo(RefundStatus.REFUND_PENDING);
        assertThat(rows("seat_reservation WHERE booking_id = " + booking.id())).isZero();
        assertThat(usedStanding()).isZero();
        assertThat(availability.availability(TRIP, 0, 3).availability().seats()).filteredOn(seat -> seat.seatId() == 2).singleElement()
                .satisfies(seat -> assertThat(seat.state()).isEqualTo(SeatState.AVAILABLE));
        assertThat(committed.seatEvents()).extracting(SeatEvent::type).contains(SeatEvent.SEAT_RELEASED, SeatEvent.STANDING_CHANGED); // after the commit
    }

    @Test
    void thePassengersCancellation_recordsTheUser_andNoReason() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);

        cancellations.cancel(Actor.user("alice-sub"), booking.id(), "should not be stored");

        assertThat(column("cancelled_by", booking.id())).isEqualTo("alice-sub");
        assertThat(column("cancelled_by_role", booking.id())).isEqualTo("USER");
        assertThat(jdbc.sql("SELECT cancel_reason FROM booking WHERE id = :id").param("id", booking.id()).query(String.class).optional()).isEmpty(); // NULL: no reason
        assertThat(column("cancelled_at", booking.id())).isNotNull();
    }

    @Test
    void anAdminsCancellation_recordsWhichAdmin_andWhy_andIsTheSameCancellation() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);

        BookingView after = cancellations.cancel(Actor.admin("admin-sub"), booking.id(), "Called support: wrong date");

        assertThat(after.status().name()).isEqualTo("CANCELLED");
        assertThat(column("cancelled_by", booking.id())).isEqualTo("admin-sub");
        assertThat(column("cancelled_by_role", booking.id())).isEqualTo("ADMIN");
        assertThat(column("cancel_reason", booking.id())).isEqualTo("Called support: wrong date");
        assertThat(rows("seat_reservation WHERE booking_id = " + booking.id())).isZero();
        assertThat(rows("audit_log WHERE action = 'CANCEL' AND admin_id = 'admin-sub'")).isEqualTo(1);
    }

    @Test
    void aPassenger_cannotCancelSomeoneElsesBooking_itIs404_andNothingChanges() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);

        assertThatThrownBy(() -> cancellations.cancel(Actor.user("bob-sub"), booking.id(), null)).isInstanceOf(BookingNotFoundException.class);
        assertThatThrownBy(() -> cancellations.cancel(Actor.user("alice-sub"), 987654, null)).isInstanceOf(BookingNotFoundException.class);
        assertThatThrownBy(() -> cancellations.cancel(Actor.admin("admin-sub"), 987654, "x")).isInstanceOf(BookingNotFoundException.class);

        assertThat(column("status", booking.id())).isEqualTo("CONFIRMED");
        assertThat(rows("outbox WHERE status = 'PENDING'")).isZero();
    }

    @Test
    void cancellingTwice_returnsTheCurrentState_andDoesNothingAgain() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        cancellations.cancel(Actor.user("alice-sub"), booking.id(), null);
        committed.clear();

        BookingView again = cancellations.cancel(Actor.user("alice-sub"), booking.id(), null);
        BookingView byAdmin = cancellations.cancel(Actor.admin("admin-sub"), booking.id(), "late");

        assertThat(again.status().name()).isEqualTo("CANCELLED");
        assertThat(byAdmin.status().name()).isEqualTo("CANCELLED");
        assertThat(rows("outbox WHERE kind = 'REFUND_REQUEST'")).isEqualTo(1);           // one refund request, not three
        assertThat(committed.seatEvents()).isEmpty();                                    // no second release
        assertThat(column("cancelled_by", booking.id())).isEqualTo("alice-sub");         // the first actor stays on record
    }

    @Test
    void twoCancellationsAtTheSameMoment_haveOneEffect() throws Exception {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BookingView>> results = new ArrayList<>();
        for (Actor actor : List.of(Actor.user("alice-sub"), Actor.admin("admin-sub"))) {
            results.add(pool.submit(() -> {
                go.await();
                return cancellations.cancel(actor, booking.id(), "support");
            }));
        }
        go.countDown();
        for (Future<BookingView> result : results) {
            assertThat(result.get().status().name()).isEqualTo("CANCELLED");
        }
        pool.shutdown();

        assertThat(rows("outbox WHERE kind = 'REFUND_REQUEST'")).isEqualTo(1);
        assertThat(usedStanding()).isZero(); // the standing places were given back once, not twice
    }

    @Test
    void onlyAConfirmedBookingCanBeCancelled() {
        BookingView hold = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(3L), List.of()));

        assertThatThrownBy(() -> cancellations.cancel(Actor.user("alice-sub"), hold.id(), null))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("not_cancellable");
        assertThat(column("status", hold.id())).isEqualTo("PENDING_PAYMENT");
    }

    // ---- the cutoff: the train has not left the booking's boarding stop -----------------------------------------------------

    @Test
    void afterTheTrainLeftTheBoardingStop_theBookingCannotBeCancelled() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        clock.set(DEPARTURE.plusSeconds(1));

        assertThatThrownBy(() -> cancellations.cancel(Actor.user("alice-sub"), booking.id(), null))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("trip_departed");
        assertThatThrownBy(() -> cancellations.cancel(Actor.admin("admin-sub"), booking.id(), "x"))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("trip_departed");

        assertThat(column("status", booking.id())).isEqualTo("CONFIRMED");
        assertThat(rows("seat_reservation WHERE booking_id = " + booking.id())).isGreaterThan(0);
        assertThat(rows("outbox WHERE kind = 'REFUND_REQUEST'")).isZero();
    }

    @Test
    void theCutoffIsTheExactDepartureTime_oneSecondBeforeStillWorks() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);

        clock.set(DEPARTURE);
        assertThatThrownBy(() -> cancellations.cancel(Actor.user("alice-sub"), booking.id(), null)).isInstanceOf(BookingConflictException.class);

        clock.set(DEPARTURE.minusSeconds(1));
        assertThat(cancellations.cancel(Actor.user("alice-sub"), booking.id(), null).status().name()).isEqualTo("CANCELLED");
    }

    @Test
    void theCutoffIsTheBookingsOwnBoardingStop_notTheFirstStopOfTheTrip() {
        // Boarding at Giza (stop 1), where the train leaves an hour after Cairo: after Cairo's departure it is still allowed.
        BookingView booking = confirmed(ALICE, 1, 3, 2);
        clock.set(DEPARTURE.plusSeconds(1800));

        assertThat(cancellations.cancel(Actor.user("alice-sub"), booking.id(), null).status().name()).isEqualTo("CANCELLED");

        BookingView late = confirmed(new AuthenticatedUser("bob-sub", "bob@example.com", "Bob"), 1, 3, 3);
        clock.set(DEPARTURE.plusSeconds(3600 + 1));
        assertThatThrownBy(() -> cancellations.cancel(Actor.user("bob-sub"), late.id(), null)).isInstanceOf(BookingConflictException.class);
    }

    // ---- the follow-up: ticket, refund, emails (from the outbox) -----------------------------------------------------------

    @Test
    void theFollowUp_isQueuedInTheSameTransaction_inOrder() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);

        cancellations.cancel(Actor.user("alice-sub"), booking.id(), null);

        assertThat(jdbc.sql("SELECT kind FROM outbox WHERE booking_id = :b AND status = 'PENDING' ORDER BY id").param("b", booking.id()).query(String.class).list())
                .containsExactly("TICKET_INVALIDATE", "REFUND_REQUEST", "CANCELLATION_EMAIL");
        assertThat(payment.refundCalls()).isEmpty(); // nothing is called inside the cancellation itself
        assertThat(tickets.invalidated()).isEmpty();
    }

    @Test
    void theDispatcher_invalidatesTheTicket_asksForTheFullRefund_andEmailsThePassenger() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        cancellations.cancel(Actor.user("alice-sub"), booking.id(), null);

        assertThat(drain()).isEqualTo(3);

        assertThat(tickets.invalidated()).containsExactly(booking.id());
        assertThat(payment.refundCalls()).containsExactly(booking.id() + ":Cancelled by the passenger.");
        assertThat(notifications.accepted()).singleElement().satisfies(email -> {
            assertThat(email.kind()).isEqualTo(NotificationPort.Kind.CANCELLATION);
            assertThat(email.to()).isEqualTo("alice@example.com");
            assertThat(email.amount()).isEqualByComparingTo(booking.amount()); // the whole amount is promised back
            assertThat(email.detail()).isEqualTo("You cancelled this booking.");
            assertThat(email.attachment()).isNull();
        });
    }

    @Test
    void anAdminsCancellationEmail_andRefundRequest_carryTheReason() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        cancellations.cancel(Actor.admin("admin-sub"), booking.id(), "Wrong date");

        drain();

        assertThat(payment.refundCalls()).containsExactly(booking.id() + ":Wrong date");
        assertThat(notifications.accepted().get(0).detail()).isEqualTo("Your booking was cancelled by our team. Reason: Wrong date");
    }

    @Test
    void whenPaymentIsDown_theBookingStaysCancelled_theRefundIsAskedAgainLater_andTheEmailIsNotHeldBack() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        cancellations.cancel(Actor.user("alice-sub"), booking.id(), null);
        payment.refundFails(new PaymentUnavailableException("Payment is down", null));

        drain();

        assertThat(column("status", booking.id())).isEqualTo("CANCELLED");
        assertThat(tickets.invalidated()).containsExactly(booking.id()); // the QR is already off
        assertThat(notifications.accepted()).hasSize(1);                // the email does not wait for the refund

        payment.reset();
        clock.advance(Duration.ofSeconds(10));
        drain();
        assertThat(payment.refundCalls()).hasSize(1);                   // and the refund is asked again, nothing is lost
        assertThat(notifications.accepted()).hasSize(1);                // still one email: it was not sent a second time
    }

    @Test
    void whenTicketIsDown_theCancellationStands_andTheRefundAndTheEmailGoOutAnyway() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        cancellations.cancel(Actor.user("alice-sub"), booking.id(), null);
        tickets.down(true);

        drain();

        assertThat(payment.refundCalls()).hasSize(1);            // the passenger's money does not wait for the ticket service
        assertThat(notifications.accepted()).hasSize(1);

        assertThat(column("status", booking.id())).isEqualTo("CANCELLED");
        assertThat(rows("seat_reservation WHERE booking_id = " + booking.id())).isZero();
        assertThat(rows("outbox WHERE kind = 'TICKET_INVALIDATE' AND status = 'PENDING' AND attempts = 1")).isEqualTo(1);
    }

    @Test
    void aRefundRequestThatKeepsFailing_isFlaggedForTheAdmin_butNeverGivenUp() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        cancellations.cancel(Actor.user("alice-sub"), booking.id(), null);
        payment.refundFails(new PaymentUnavailableException("Payment is down", null));

        for (int round = 0; round < 12; round++) {
            clock.advance(Duration.ofMinutes(10));
            drain();
        }

        assertThat(column("refund_status", booking.id())).isEqualTo("REFUND_FAILED");                       // visible on the booking, not only in a log
        assertThat(rows("outbox WHERE kind = 'REFUND_REQUEST' AND status = 'PENDING'")).isEqualTo(1);       // and still being tried

        payment.reset();
        clock.advance(Duration.ofMinutes(10));
        drain();
        assertThat(payment.refundCalls()).hasSize(1);
        assertThat(rows("outbox WHERE kind = 'REFUND_REQUEST' AND status = 'SENT'")).isEqualTo(1);
    }

    @Test
    void ifTheBoardingStopIsMissing_theCancellationIsRefused_notAllowed() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        jdbc.sql("DELETE FROM trip_stop WHERE trip_id = :t AND stop_index = 0").param("t", TRIP).update();

        assertThatThrownBy(() -> cancellations.cancel(Actor.user("alice-sub"), booking.id(), null))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("trip_departed");
    }

    @Test
    void aBookingWithNoPaymentToRefund_isFlaggedForAnAdmin() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        cancellations.cancel(Actor.user("alice-sub"), booking.id(), null);
        payment.refundAnswers(RefundPort.Result.NO_PAYMENT);

        drain();

        assertThat(column("refund_status", booking.id())).isEqualTo("REFUND_FAILED");
    }

    @Test
    void whenPaymentReportsTheRefundDone_thePassengerIsEmailedOnce_andREFUND_FAILED_isNotEmailed() {
        BookingView booking = confirmed(ALICE, 0, 3, 2);
        cancellations.cancel(Actor.user("alice-sub"), booking.id(), null);
        drain();
        notifications.reset();

        results.refundStatus(booking.id(), RefundStatus.REFUNDED);
        results.refundStatus(booking.id(), RefundStatus.REFUNDED); // a repeated report
        drain();

        assertThat(notifications.accepted()).singleElement().satisfies(email -> {
            assertThat(email.kind()).isEqualTo(NotificationPort.Kind.REFUND);
            assertThat(email.amount()).isEqualByComparingTo(booking.amount());
        });
        assertThat(column("refund_status", booking.id())).isEqualTo("REFUNDED");

        BookingView other = confirmed(new AuthenticatedUser("bob-sub", "bob@example.com", "Bob"), 0, 3, 3);
        cancellations.cancel(Actor.user("bob-sub"), other.id(), null);
        drain();
        notifications.reset();
        results.refundStatus(other.id(), RefundStatus.REFUND_FAILED);
        drain();
        assertThat(notifications.accepted()).isEmpty(); // a failed refund is for an admin to follow up, not an email to the passenger
    }

    @Test
    void theTicketEmailThatWasStillQueued_isNotSentForACancelledBooking() {
        BookingView hold = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of()));
        confirmations.confirm(hold.id()); // the ticket email is queued but not sent yet
        cancellations.cancel(Actor.user("alice-sub"), hold.id(), null);

        drain();

        assertThat(tickets.issueCalls()).isEmpty(); // no ticket is made for a booking that is already cancelled
        assertThat(notifications.accepted()).extracting(NotificationPort.Email::kind).containsExactly(NotificationPort.Kind.CANCELLATION);
    }

    // ---- My bookings ----------------------------------------------------------------------------------------------------

    @Test
    void myBookings_listsTheCallersHoldsConfirmedAndCancelledBookings_newestFirst_withWhatCanBeCancelled() {
        BookingView cancelled = confirmed(ALICE, 0, 3, 2);
        cancellations.cancel(Actor.user("alice-sub"), cancelled.id(), null);
        BookingView kept = confirmed(ALICE, 0, 3, 3);
        BookingView expired = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(1L), List.of()));
        jdbc.sql("UPDATE booking SET status = 'EXPIRED' WHERE id = :id").param("id", expired.id()).update();
        confirmed(new AuthenticatedUser("bob-sub", "bob@example.com", "Bob"), 0, 2, 4);

        List<MyBooking> mine = queries.listOwn("alice-sub");

        assertThat(mine).extracting(m -> m.booking().id()).containsExactly(kept.id(), cancelled.id()); // an expired hold is not a booking; Bob's is not hers
        assertThat(mine.get(0).cancellable()).isTrue();
        assertThat(mine.get(1).cancellable()).isFalse();
        assertThat(mine.get(1).booking().refundStatus()).isEqualTo(RefundStatus.REFUND_PENDING);
        clock.set(DEPARTURE.plusSeconds(60));
        assertThat(queries.listOwn("alice-sub").get(0).cancellable()).isFalse(); // the train has left
    }

    @Test
    void myBookings_showsTheActiveHold_andAPaidButExpiredBookingWithItsRefund() {
        BookingView hold = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of()));
        BookingView paidThenExpired = holds.hold(new AuthenticatedUser("alice2-sub", "a2@example.com", "A2"), new HoldRequest(TRIP, 0, 3, List.of(3L), List.of()));
        jdbc.sql("UPDATE booking SET status = 'EXPIRED', refund_status = 'REFUND_PENDING' WHERE id = :id").param("id", paidThenExpired.id()).update();

        assertThat(queries.listOwn("alice-sub")).extracting(m -> m.booking().status().name()).containsExactly("PENDING_PAYMENT");
        assertThat(queries.listOwn("alice2-sub")).extracting(m -> m.booking().id()).containsExactly(paidThenExpired.id());
        assertThat(hold.id()).isNotEqualTo(paidThenExpired.id());
    }
}
