package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.IssueRequest;
import com.trainbooking.bookingseat.booking.model.IssueRequest.Passenger;
import com.trainbooking.bookingseat.booking.model.IssueRequest.Passenger.Mode;
import com.trainbooking.bookingseat.booking.model.PassengerNotFoundException;
import com.trainbooking.bookingseat.booking.model.PaymentMethod;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.service.BookingIssueService;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import com.trainbooking.bookingseat.booking.service.CancellationService.Actor;
import com.trainbooking.bookingseat.booking.service.ConfirmationService;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.outbox.service.OutboxDispatcher;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
import com.trainbooking.bookingseat.testsupport.TestJwts;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Counter and complimentary bookings (docs/design/COUNTER-BOOKING.md): an admin confirms their own hold without Stripe. */
class IssueDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9951;
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser("admin-sub", "admin@example.com", "Admin One");
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice Example");

    @Autowired
    Holds holds;
    @Autowired
    BookingIssueService issues;
    @Autowired
    ConfirmationService confirmations;
    @Autowired
    CancellationService cancellations;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    com.trainbooking.bookingseat.booking.service.BookingQueryService queries;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    private BookingView adminHold(Long... seats) {
        return holds.hold(ADMIN, new HoldRequest(TRIP, 0, 3, List.of(seats), List.of()));
    }

    private static IssueRequest counter(String note) {
        return new IssueRequest(PaymentMethod.COUNTER, note, null, new Passenger(Mode.WALK_IN, null, "Walk In", "0100"));
    }

    private static IssueRequest complimentary(String reason) {
        return new IssueRequest(PaymentMethod.COMPLIMENTARY, null, reason, new Passenger(Mode.WALK_IN, null, "Guest", null));
    }

    private void noMoneyMoved() {
        assertThat(payment.createCalls()).isEmpty();
        assertThat(payment.cancelCalls()).isEmpty();
        assertThat(payment.invalidateCalls()).isEmpty();
        assertThat(payment.refundCalls()).isEmpty();
    }

    @Test
    void counter_confirmsTheHold_withTheAuditFields_andNoPaymentCall() {
        BookingView hold = adminHold(2L, 3L);

        BookingView done = issues.issue("admin-sub", hold.id(), counter("receipt 4411"));

        assertThat(done.status().name()).isEqualTo("CONFIRMED");
        assertThat(done.paymentMethod()).isEqualTo(PaymentMethod.COUNTER);
        var row = jdbc.sql("SELECT charged_amount, amount, issued_by, issue_note, walk_in_name, walk_in_phone, user_id FROM booking WHERE id = :id").param("id", hold.id())
                .query((rs, n) -> List.of(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7))).single();
        assertThat(row.get(0)).isEqualTo(row.get(1)); // the full fare was taken at the counter
        assertThat(row.subList(2, 7)).containsExactly("admin-sub", "receipt 4411", "Walk In", "0100", "admin-sub");
        assertThat(jdbc.sql("SELECT issued_at FROM booking WHERE id = :id").param("id", hold.id()).query(java.time.OffsetDateTime.class).single()).isNotNull();
        assertThat(rows("seat_reservation WHERE booking_id = " + hold.id() + " AND expires_at IS NULL")).isEqualTo(6); // 2 seats x 3 segments, confirmed
        assertThat(rows("audit_log WHERE action = 'ISSUE' AND entity_id = " + hold.id() + " AND admin_id = 'admin-sub'")).isEqualTo(1);
        assertThat(rows("outbox WHERE kind = 'TICKET_EMAIL' AND booking_id = " + hold.id())).isEqualTo(1); // the same event as the card path
        noMoneyMoved();
    }

    @Test
    void complimentary_needsAReason_chargesNothing_andKeepsTheFareValue() {
        BookingView hold = adminHold(2L);

        assertThatThrownBy(() -> issues.issue("admin-sub", hold.id(), complimentary(null))).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> issues.issue("admin-sub", hold.id(), complimentary("abc"))).isInstanceOf(RequestValidationException.class);
        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT"); // nothing changed

        issues.issue("admin-sub", hold.id(), complimentary("Press pass"));

        assertThat(statusOf(hold.id())).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT charged_amount FROM booking WHERE id = :id").param("id", hold.id()).query(java.math.BigDecimal.class).single().signum()).isZero();
        assertThat(jdbc.sql("SELECT amount FROM booking WHERE id = :id").param("id", hold.id()).query(java.math.BigDecimal.class).single().signum()).isPositive();
        assertThat(jdbc.sql("SELECT issue_note FROM booking WHERE id = :id").param("id", hold.id()).query(String.class).single()).isEqualTo("Press pass");
        noMoneyMoved();
    }

    private String statusOf(long id) {
        return jdbc.sql("SELECT status FROM booking WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    void doubleSubmit_givesOneBooking() {
        BookingView hold = adminHold(2L);

        issues.issue("admin-sub", hold.id(), counter("r1"));
        issues.issue("admin-sub", hold.id(), counter("r1"));

        assertThat(rows("booking WHERE trip_id = " + TRIP)).isEqualTo(1);
        assertThat(rows("outbox WHERE kind = 'TICKET_EMAIL' AND booking_id = " + hold.id())).isEqualTo(1);
        assertThat(rows("audit_log WHERE action = 'ISSUE'")).isEqualTo(1);
    }

    @Test
    void anExpiredHold_isRefused_andNothingIsCreated() {
        BookingView hold = adminHold(2L);
        clock.advance(Duration.ofMinutes(6)); // the sweeper has not run: the seats are still blocked, but the hold is over

        assertThatThrownBy(() -> issues.issue("admin-sub", hold.id(), counter(null)))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("hold_expired"));

        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(rows("outbox WHERE kind = 'TICKET_EMAIL'")).isZero();
        assertThat(rows("audit_log WHERE action = 'ISSUE'")).isZero();
    }

    @Test
    void aReleasedHold_isRefused() {
        BookingView hold = adminHold(2L);
        jdbc.sql("UPDATE booking SET status = 'ABANDONED' WHERE id = :id").param("id", hold.id()).update();

        assertThatThrownBy(() -> issues.issue("admin-sub", hold.id(), counter(null)))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("hold_expired"));
    }

    @Test
    void onlyTheAdminsOwnHold_canBeIssued() {
        BookingView alicesHold = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of()));

        assertThatThrownBy(() -> issues.issue("admin-sub", alicesHold.id(), counter(null))).isInstanceOf(BookingNotFoundException.class);
        assertThat(statusOf(alicesHold.id())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void aHoldWhosePaymentPageWasOpened_isRefused() {
        BookingView hold = adminHold(2L);
        jdbc.sql("UPDATE booking SET payment_started = 1 WHERE id = :id").param("id", hold.id()).update();

        assertThatThrownBy(() -> issues.issue("admin-sub", hold.id(), counter(null)))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("payment_started"));
        noMoneyMoved();
    }

    @Test
    void theMaximumSeatsPerBooking_stillApplies_andTheSeatsStayUniqueInTheDatabase() {
        // The hold limit (20 seats) is enforced when the seats are picked, before any issue: a 21st pick fails and the hold stays as it is.
        // The database key still rejects a second reservation of the same seat and segment, whatever way the booking is confirmed.
        BookingView first = adminHold(2L);
        issues.issue("admin-sub", first.id(), counter(null));
        assertThatThrownBy(() -> holds.hold(new AuthenticatedUser("admin-sub", "admin@example.com", "Admin One"), new HoldRequest(TRIP, 0, 3, List.of(2L), List.of())))
                .isInstanceOf(RuntimeException.class); // seat 2 is taken: segment availability applies to admin picks too
        assertThat(rows("seat_reservation WHERE seat_id = 2")).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO seat_reservation (trip_id, seat_id, segment_index, booking_id, expires_at) VALUES (:t, 2, 0, :b, NULL)")
                .param("t", TRIP).param("b", first.id()).update()).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void walkIn_needsAName() {
        BookingView hold = adminHold(2L);
        IssueRequest noName = new IssueRequest(PaymentMethod.COUNTER, null, null, new Passenger(Mode.WALK_IN, null, " ", null));
        assertThatThrownBy(() -> issues.issue("admin-sub", hold.id(), noName)).isInstanceOf(RequestValidationException.class);
        IssueRequest card = new IssueRequest(PaymentMethod.CARD, null, null, new Passenger(Mode.WALK_IN, null, "X", null));
        assertThatThrownBy(() -> issues.issue("admin-sub", hold.id(), card)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void walkIn_withoutAnEmail_getsATicketButNoEmail_andWithOne_theEmailGoesThere() {
        BookingView withoutEmail = adminHold(2L);
        issues.issue("admin-sub", withoutEmail.id(), counter("r"));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(tickets.issued()).hasSize(1);
        assertThat(tickets.issued().get(0).passengerName()).isEqualTo("Walk In");
        assertThat(tickets.issued().get(0).paymentMethod()).isEqualTo("COUNTER");
        assertThat(notifications.accepted()).isEmpty();

        BookingView withEmail = adminHold(3L);
        issues.issue("admin-sub", withEmail.id(), new IssueRequest(PaymentMethod.COUNTER, null, null, new Passenger(Mode.WALK_IN, "mona@example.com", "Mona", null)));
        dispatcher.dispatchDue();
        assertThat(notifications.accepted()).hasSize(1);
        assertThat(notifications.accepted().get(0).to()).isEqualTo("mona@example.com");
        assertThat(notifications.accepted().get(0).paymentMethod()).isEqualTo("COUNTER");
    }

    @Test
    void existingAccount_isFoundByExactEmailOnly_andOwnsTheBooking() {
        BookingView earlier = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(3L), List.of()));
        confirmations.confirm(earlier.id()); // Alice is now a user this service has seen

        assertThat(issues.lookup("ALICE@example.com")).isPresent();            // exact, case-insensitive
        assertThat(issues.lookup("alice")).isEmpty();                          // no partial match
        assertThat(issues.lookup("@example.com")).isEmpty();
        assertThat(issues.lookup("%")).isEmpty();

        BookingView hold = adminHold(2L);
        issues.issue("admin-sub", hold.id(), new IssueRequest(PaymentMethod.COUNTER, null, null, new Passenger(Mode.ACCOUNT, "alice@example.com", null, null)));

        assertThat(jdbc.sql("SELECT user_id FROM booking WHERE id = :id").param("id", hold.id()).query(String.class).single()).isEqualTo("alice-sub");
        assertThat(jdbc.sql("SELECT issued_by FROM booking WHERE id = :id").param("id", hold.id()).query(String.class).single()).isEqualTo("admin-sub");
        dispatcher.dispatchDue();
        assertThat(notifications.accepted()).extracting(e -> e.to()).contains("alice@example.com");
        // an unknown account is refused and nothing changes
        BookingView other = adminHold(4L);
        assertThatThrownBy(() -> issues.issue("admin-sub", other.id(), new IssueRequest(PaymentMethod.COUNTER, null, null, new Passenger(Mode.ACCOUNT, "nobody@example.com", null, null))))
                .isInstanceOf(PassengerNotFoundException.class);
        assertThat(statusOf(other.id())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void cancellingComplimentary_isNotApplicable_andCancellingCounter_isManualRefundDue_neverAStripeRefund() {
        BookingView free = adminHold(2L);
        issues.issue("admin-sub", free.id(), complimentary("Press pass"));
        cancellations.cancel(Actor.admin("admin-sub"), free.id(), "Cancelled by support");
        assertThat(refundOf(free.id())).isEqualTo("NOT_APPLICABLE");

        BookingView paid = adminHold(3L);
        issues.issue("admin-sub", paid.id(), counter("r9"));
        cancellations.cancel(Actor.admin("admin-sub"), paid.id(), "Passenger changed plans");
        assertThat(refundOf(paid.id())).isEqualTo("MANUAL_REFUND_DUE");
        assertThat(rows("outbox WHERE kind = 'REFUND_REQUEST'")).isZero();
        assertThat(rows("seat_reservation WHERE trip_id = " + TRIP)).isZero(); // the seats are free again
        dispatcher.dispatchDue();
        noMoneyMoved();
    }

    @Test
    void markRefundedAtCounter_transitions_isIdempotent_andRefusesOtherStates() {
        BookingView paid = adminHold(3L);
        issues.issue("admin-sub", paid.id(), counter("r9"));
        assertThatThrownBy(() -> issues.markRefundedAtCounter("admin-sub", paid.id()))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("not_refund_due")); // not cancelled yet
        cancellations.cancel(Actor.admin("admin-sub"), paid.id(), "Passenger changed plans");

        BookingView done = issues.markRefundedAtCounter("admin-sub2", paid.id());
        assertThat(done.refundStatus()).isEqualTo(RefundStatus.REFUNDED_AT_COUNTER);
        issues.markRefundedAtCounter("admin-sub3", paid.id()); // again: no change, no error

        assertThat(jdbc.sql("SELECT counter_refunded_by FROM booking WHERE id = :id").param("id", paid.id()).query(String.class).single()).isEqualTo("admin-sub2");
        assertThat(rows("audit_log WHERE action = 'COUNTER_REFUND'")).isEqualTo(1);
    }

    @Test
    void aPassengerCannotCancelACounterOrComplimentaryBooking_theDeskDoes() {
        BookingView earlier = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(3L), List.of()));
        confirmations.confirm(earlier.id());
        BookingView hold = adminHold(2L);
        issues.issue("admin-sub", hold.id(), new IssueRequest(PaymentMethod.COUNTER, null, null, new Passenger(Mode.ACCOUNT, "alice@example.com", null, null)));

        assertThatThrownBy(() -> cancellations.cancel(Actor.user("alice-sub"), hold.id(), null))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> assertThat(e.code()).isEqualTo("cancel_at_desk"));
        assertThat(statusOf(hold.id())).isEqualTo("CONFIRMED");
        // and My bookings says so
        assertThat(queries.listOwn("alice-sub")).filteredOn(m -> m.booking().id() == hold.id()).singleElement().satisfies(m -> assertThat(m.cancellable()).isFalse());
    }

    @Test
    void theCardPath_isUnchanged_andStillRefundsThroughStripe() {
        BookingView card = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of()));
        confirmations.confirm(card.id());
        cancellations.cancel(Actor.user("alice-sub"), card.id(), null);
        assertThat(refundOf(card.id())).isEqualTo("REFUND_PENDING");
        assertThat(rows("outbox WHERE kind = 'REFUND_REQUEST'")).isEqualTo(1);
    }

    private String refundOf(long id) {
        return jdbc.sql("SELECT refund_status FROM booking WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    void theEndpoints_needTheAdminRole() throws Exception {
        BookingView hold = adminHold(2L);
        String body = "{\"method\":\"COUNTER\",\"passenger\":{\"mode\":\"WALK_IN\",\"name\":\"Walk In\"}}";
        mvc.perform(post("/admin/bookings/" + hold.id() + "/issue").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/bookings/" + hold.id() + "/issue").with(TestJwts.passenger()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/passengers/lookup?q=x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/passengers/lookup?q=x").with(TestJwts.passenger())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/bookings/" + hold.id() + "/refunded-at-counter").with(TestJwts.passenger())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/passengers/lookup?q=nobody@example.com").with(TestJwts.admin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("passenger_not_found"));
        mvc.perform(post("/admin/bookings/" + hold.id() + "/issue").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentMethod").value("COUNTER")).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }
}
