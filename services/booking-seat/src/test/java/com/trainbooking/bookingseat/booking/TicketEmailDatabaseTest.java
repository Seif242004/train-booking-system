package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.service.ConfirmationService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.booking.service.TicketResendService;
import com.trainbooking.bookingseat.notification.NotificationPort;
import com.trainbooking.bookingseat.outbox.service.OutboxDispatcher;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Flow 4, last part: a confirmed booking gets its PDF ticket and its email, made afterwards from the outbox with retries; and neither
 * the ticket nor the email can ever undo a confirmation (CLAUDE.md: tickets and emails are asynchronous with retries, an email failure
 * never cancels a booking).
 */
class TicketEmailDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9501;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice Example");

    @Autowired
    Holds holds;
    @Autowired
    HoldService holdService;
    @Autowired
    ConfirmationService confirmations;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    TicketResendService resends;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    /** Seat 2 (SECOND) and two standing places in coach 30 (THIRD) for the whole trip. */
    private BookingView aHold() {
        return holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of(new HoldRequest.StandingRequest(30L, 2))));
    }

    private BookingView aConfirmedBooking() {
        BookingView hold = aHold();
        confirmations.confirm(hold.id());
        return hold;
    }

    private String statusOf(long id) {
        return jdbc.sql("SELECT status FROM booking WHERE id = :id").param("id", id).query(String.class).single();
    }

    // ---- queued with the confirmation --------------------------------------------------------------------------------

    @Test
    void confirming_queuesTheTicketEmail_inTheSameTransaction() {
        BookingView hold = aHold();

        confirmations.confirm(hold.id());

        assertThat(rows("outbox WHERE kind = 'TICKET_EMAIL' AND status = 'PENDING' AND booking_id = " + hold.id())).isEqualTo(1);
        assertThat(tickets.issueCalls()).isEmpty();   // nothing is made inside the confirmation itself
        assertThat(notifications.accepted()).isEmpty();
    }

    @Test
    void aConfirmationThatIsRolledBack_queuesNothing() {
        BookingView hold = aHold();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            confirmations.confirm(hold.id());
            throw new IllegalStateException("something failed after the confirmation");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(rows("outbox")).isZero();
    }

    @Test
    void confirmingTwice_queuesOnce() {
        BookingView hold = aHold();

        confirmations.confirm(hold.id());
        confirmations.confirm(hold.id());

        assertThat(rows("outbox")).isEqualTo(1);
    }

    @Test
    void aHoldThatIsReleased_getsNoTicket() {
        BookingView hold = aHold();
        holdService.releaseAll(ALICE, hold.id());

        assertThat(rows("outbox")).isZero();
    }

    // ---- the work ----------------------------------------------------------------------------------------------------

    @Test
    void theDispatcher_makesTheTicketWithEverythingOnIt_andEmailsItWithThePdfAttached() {
        BookingView booking = aConfirmedBooking();

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        assertThat(tickets.issued()).singleElement().satisfies(ticket -> {
            assertThat(ticket.reference()).isEqualTo(booking.reference());
            assertThat(ticket.passengerName()).isEqualTo("Alice Example");
            assertThat(ticket.trainNumber()).isEqualTo("T100");
            assertThat(ticket.fromStation()).isEqualTo("Cairo");
            assertThat(ticket.toStation()).isEqualTo("Alexandria");
            assertThat(ticket.lines()).extracting(l -> l.type() + ":" + l.coachCode() + ":" + l.seatNumber() + ":" + l.qty())
                    .containsExactlyInAnyOrder("SEAT:B:B1:1", "STANDING:C:null:2");
            assertThat(ticket.amount()).isEqualByComparingTo(booking.amount()); // the amount the server worked out
        });
        assertThat(notifications.accepted()).singleElement().satisfies(email -> {
            assertThat(email.kind()).isEqualTo(NotificationPort.Kind.TICKET);
            assertThat(email.to()).isEqualTo("alice@example.com");
            assertThat(email.toName()).isEqualTo("Alice Example");
            assertThat(email.reference()).isEqualTo(booking.reference());
            assertThat(email.lines()).hasSize(2);
            assertThat(email.lines()).anyMatch(l -> l.startsWith("Seat B1, coach B (second): ") && l.endsWith(" EGP"));
            assertThat(email.lines()).anyMatch(l -> l.startsWith("2 standing places, coach C (third): ") && l.endsWith(" EGP"));
            assertThat(new String(Base64.getDecoder().decode(email.attachment().contentBase64()))).startsWith("%PDF");
            assertThat(email.attachment().fileName()).isEqualTo("ticket-" + booking.reference() + ".pdf");
            assertThat(email.attachment().contentType()).isEqualTo("application/pdf");
        });
        assertThat(rows("outbox WHERE status = 'SENT'")).isEqualTo(1);
        assertThat(dispatcher.dispatchDue()).isZero(); // done once
    }

    @Test
    void whenTicketIsDown_theBookingStaysConfirmed_andTheJobWaitsAndRetriesWithABackoff() {
        BookingView booking = aConfirmedBooking();
        tickets.down(true);

        assertThat(dispatcher.dispatchDue()).isZero();

        assertThat(statusOf(booking.id())).isEqualTo("CONFIRMED");        // a ticket that cannot be made never cancels a booking
        assertThat(rows("outbox WHERE status = 'PENDING' AND attempts = 1")).isEqualTo(1);
        dispatcher.dispatchDue();                                           // the same second: not due, not tried again
        assertThat(tickets.issueCalls()).isEmpty();

        tickets.down(false);
        clock.advance(Duration.ofSeconds(10));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(notifications.accepted()).hasSize(1);
    }

    @Test
    void whenNotificationIsDown_theBookingStaysConfirmed_andTheEmailIsSentOnceItIsBack_withoutASecondTicket() {
        BookingView booking = aConfirmedBooking();
        notifications.down(true);

        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(statusOf(booking.id())).isEqualTo("CONFIRMED");          // an email failure never cancels a booking
        assertThat(tickets.issued()).hasSize(1);                            // the ticket was made

        notifications.down(false);
        clock.advance(Duration.ofSeconds(10));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        assertThat(tickets.issued()).hasSize(1);                            // the retry did not make another
        assertThat(notifications.accepted()).hasSize(1);
        assertThat(statusOf(booking.id())).isEqualTo("CONFIRMED");
    }

    @Test
    void aBookingThatWasCancelledBeforeTheJobRan_getsNoTicketEmail() {
        BookingView booking = aConfirmedBooking();
        jdbc.sql("UPDATE booking SET status = 'CANCELLED' WHERE id = :id").param("id", booking.id()).update();

        assertThat(dispatcher.dispatchDue()).isEqualTo(1); // the job is done: there is nothing to send

        assertThat(tickets.issueCalls()).isEmpty();
        assertThat(notifications.accepted()).isEmpty();
    }

    @Test
    void aJobThatNeverSucceeds_isMarkedFailed_afterManyAttempts_andNeverTouchesTheBooking() {
        BookingView booking = aConfirmedBooking();
        jdbc.sql("UPDATE outbox SET attempts = 299").update();
        tickets.down(true);

        dispatcher.dispatchDue();

        assertThat(rows("outbox WHERE status = 'FAILED'")).isEqualTo(1);
        assertThat(statusOf(booking.id())).isEqualTo("CONFIRMED");
    }

    // ---- resend (FR-T4) ----------------------------------------------------------------------------------------------

    @Test
    void resend_queuesAnotherEmail_withItsOwnKey_andTheSameTicket() {
        BookingView booking = aConfirmedBooking();
        dispatcher.dispatchDue();

        assertThat(resends.resend("alice-sub", booking.id())).isTrue();
        dispatcher.dispatchDue();

        assertThat(notifications.accepted()).hasSize(2);
        assertThat(notifications.accepted().get(0).dedupeKey()).isNotEqualTo(notifications.accepted().get(1).dedupeKey());
        assertThat(tickets.issued()).hasSize(1); // one ticket per booking
    }

    @Test
    void resend_whileOneIsStillWaiting_addsNothing() {
        BookingView booking = aConfirmedBooking();

        assertThat(resends.resend("alice-sub", booking.id())).isFalse(); // the confirmation's own email has not gone out yet
        assertThat(rows("outbox")).isEqualTo(1);
    }

    @Test
    void resend_isForTheOwnerOfAConfirmedBookingOnly() {
        BookingView confirmed = aConfirmedBooking();
        dispatcher.dispatchDue();

        assertThatThrownBy(() -> resends.resend("bob-sub", confirmed.id())).isInstanceOf(BookingNotFoundException.class);
        assertThatThrownBy(() -> resends.resend("alice-sub", 987654)).isInstanceOf(BookingNotFoundException.class);
        BookingView hold = holds.hold(new AuthenticatedUser("carol-sub", "carol@example.com", "Carol"), new HoldRequest(TRIP, 0, 3, List.of(3L), List.of()));
        assertThatThrownBy(() -> resends.resend("carol-sub", hold.id()))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("not_confirmed");
        assertThat(rows("outbox WHERE status = 'PENDING'")).isZero();
    }
}
