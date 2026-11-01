package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldExpiredException;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.PaymentStart;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.service.ExpiryService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.booking.service.PaymentResultService;
import com.trainbooking.bookingseat.booking.service.PaymentStartService;
import com.trainbooking.bookingseat.payment.PaymentPort.CancelOutcome;
import com.trainbooking.bookingseat.payment.PaymentRejectedException;
import com.trainbooking.bookingseat.payment.PaymentUnavailableException;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Flow 4 on the Booking side: "Continue to payment" asks Payment for a payment only while the hold is active, with the amount the
 * server worked out; and what Payment reports back (a payment succeeded, a refund changed) confirms the booking or asks for a refund.
 */
class PaymentFlowDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9001;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice");

    @Autowired
    Holds holds;
    @Autowired
    HoldService holdService;
    @Autowired
    PaymentStartService starts;
    @Autowired
    PaymentResultService results;
    @Autowired
    ExpiryService expiry;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    /** Seats 2 and 3 (SECOND) for the whole trip: 2 x 15 = 30 EGP. */
    private BookingView aHold() {
        return holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L, 3L), List.of()));
    }

    private String statusOf(long id) {
        return jdbc.sql("SELECT status FROM booking WHERE id = :id").param("id", id).query(String.class).single();
    }

    // ---- Continue to payment ------------------------------------------------------------------------------------------

    @Test
    void continueToPayment_asksPaymentForTheServerAmount_outsideAnyTransaction_andKeepsTheTimer() {
        BookingView hold = aHold();
        clock.advance(Duration.ofMinutes(1));

        PaymentStart start = starts.start("alice-sub", hold.id());

        assertThat(payment.createCalls()).containsExactly(hold.id() + ":30.00:EGP");
        assertThat(payment.createInTransaction()).containsExactly(false); // a call to another service never holds a transaction open
        assertThat(start.clientSecret()).isEqualTo("pi_test_" + hold.id() + "_secret");
        assertThat(start.amount()).isEqualByComparingTo("30.00");
        assertThat(start.expiresAt()).isEqualTo(hold.expiresAt()); // the remaining time, not a fresh 5:00
        assertThat(start.serverTime()).isAfter(hold.serverTime());
        assertThat(jdbc.sql("SELECT payment_started FROM booking WHERE id = :id").param("id", hold.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void continueToPayment_isOnlyForTheOwner() {
        BookingView hold = aHold();

        assertThatThrownBy(() -> starts.start("bob-sub", hold.id())).isInstanceOf(BookingNotFoundException.class);
        assertThatThrownBy(() -> starts.start("alice-sub", 987654)).isInstanceOf(BookingNotFoundException.class);
        assertThat(payment.createCalls()).isEmpty();
    }

    @Test
    void continueToPayment_isRefusedOnceTheTimeIsUp_evenBeforeTheSweeperReleasedTheSeats() {
        BookingView hold = aHold();
        clock.advance(Duration.ofMinutes(5)); // exactly at expires_at; the sweeper has not run

        assertThatThrownBy(() -> starts.start("alice-sub", hold.id())).isInstanceOf(HoldExpiredException.class);

        assertThat(payment.createCalls()).isEmpty();
        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT"); // still blocking the seats, as the rules say
    }

    @Test
    void continueToPayment_afterTheHoldEnded_orWasPaid_isRefusedWithTheRightReason() {
        BookingView expired = aHold();
        clock.advance(Duration.ofMinutes(6));
        expiry.sweep();
        assertThatThrownBy(() -> starts.start("alice-sub", expired.id())).isInstanceOf(HoldExpiredException.class);

        BookingView abandoned = aHold();
        holdService.releaseAll(ALICE, abandoned.id());
        assertThatThrownBy(() -> starts.start("alice-sub", abandoned.id()))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("not_a_hold");

        BookingView paid = aHold();
        results.paymentSucceeded(paid.id(), new BigDecimal("30.00"));
        assertThatThrownBy(() -> starts.start("alice-sub", paid.id()))
                .isInstanceOf(BookingConflictException.class).extracting("code").isEqualTo("already_paid");

        assertThat(payment.createCalls()).isEmpty();
    }

    @Test
    void whenPaymentIsDown_theUserSeesAnError_andTheNextChangeStillCancelsThePayment() {
        BookingView hold = aHold();
        payment.createFails(new PaymentUnavailableException("down", null));

        assertThatThrownBy(() -> starts.start("alice-sub", hold.id())).isInstanceOf(PaymentUnavailableException.class);

        // The flag was set before Payment was asked: a payment may exist, so releasing must go through Payment.
        payment.alwaysInvalidateAnswers(CancelOutcome.CANCELLED);
        holdService.release(ALICE, hold.id(), new com.trainbooking.bookingseat.booking.model.ReleaseRequest(2L, null, null));
        assertThat(payment.invalidateCalls()).containsExactly(hold.id());
    }

    @Test
    void anOutdatedOpenPayment_isInvalidatedAndMadeAgain_once() {
        BookingView hold = aHold();
        payment.createFails(new PaymentRejectedException("payment_outdated", "x"));

        assertThatThrownBy(() -> starts.start("alice-sub", hold.id())).isInstanceOf(PaymentRejectedException.class);

        assertThat(payment.invalidateCalls()).containsExactly(hold.id());
        assertThat(payment.createCalls()).hasSize(2); // not a loop
    }

    // ---- what Payment reports -----------------------------------------------------------------------------------------

    @Test
    void aPayment_forTheRightAmount_confirmsTheBooking_andIsSafeToReportAgain() {
        BookingView hold = aHold();

        assertThat(results.paymentSucceeded(hold.id(), new BigDecimal("30"))).isEqualTo(PaymentResultService.Result.CONFIRMED);
        assertThat(results.paymentSucceeded(hold.id(), new BigDecimal("30.00"))).isEqualTo(PaymentResultService.Result.CONFIRMED);

        assertThat(statusOf(hold.id())).isEqualTo("CONFIRMED");
        assertThat(rows("seat_reservation WHERE booking_id = " + hold.id() + " AND expires_at IS NOT NULL")).isZero(); // the sweeper never releases them
    }

    @Test
    void aPayment_thatArrivesAfterTheFiveMinutesButBeforeTheSweeper_stillWins() {
        BookingView hold = aHold();
        clock.advance(Duration.ofMinutes(5).plusSeconds(2)); // the user paid at the last second; the hold is not released yet

        assertThat(results.paymentSucceeded(hold.id(), new BigDecimal("30.00"))).isEqualTo(PaymentResultService.Result.CONFIRMED);

        assertThat(statusOf(hold.id())).isEqualTo("CONFIRMED");
        expiry.sweep();
        assertThat(statusOf(hold.id())).isEqualTo("CONFIRMED"); // and the sweeper leaves it alone
        assertThat(rows("seat_reservation WHERE booking_id = " + hold.id())).isEqualTo(6);
    }

    @Test
    void aPayment_forTheWrongAmount_isNeverConfirmed_theHoldEnds_andTheMoneyIsToBeRefunded() {
        BookingView hold = aHold();

        assertThat(results.paymentSucceeded(hold.id(), new BigDecimal("15.00"))).isEqualTo(PaymentResultService.Result.REFUND_REQUIRED);

        assertThat(statusOf(hold.id())).isEqualTo("EXPIRED");
        assertThat(rows("seat_reservation WHERE booking_id = " + hold.id())).isZero();
        assertThat(rows("audit_log WHERE action = 'PAYMENT_MISMATCH'")).isEqualTo(1);
    }

    @Test
    void aPayment_forAHoldThatIsGone_isToBeRefunded() {
        BookingView expired = aHold();
        clock.advance(Duration.ofMinutes(6));
        expiry.sweep();
        BookingView abandoned = aHold();
        holdService.releaseAll(ALICE, abandoned.id());

        assertThat(results.paymentSucceeded(expired.id(), new BigDecimal("30.00"))).isEqualTo(PaymentResultService.Result.REFUND_REQUIRED);
        assertThat(results.paymentSucceeded(abandoned.id(), new BigDecimal("30.00"))).isEqualTo(PaymentResultService.Result.REFUND_REQUIRED);

        assertThat(statusOf(expired.id())).isEqualTo("EXPIRED"); // the booking is not resurrected
        assertThat(statusOf(abandoned.id())).isEqualTo("ABANDONED");
    }

    @Test
    void aPayment_forAnUnknownBooking_isNotFound() {
        assertThatThrownBy(() -> results.paymentSucceeded(424242, new BigDecimal("30.00"))).isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void theRefundStatus_isMirroredOnTheBooking() {
        BookingView hold = aHold();

        results.refundStatus(hold.id(), RefundStatus.REFUND_PENDING);
        results.refundStatus(hold.id(), RefundStatus.REFUND_PENDING); // repeated: nothing new
        results.refundStatus(hold.id(), RefundStatus.REFUNDED);

        assertThat(jdbc.sql("SELECT refund_status FROM booking WHERE id = :id").param("id", hold.id()).query(String.class).single()).isEqualTo("REFUNDED");
        assertThat(rows("audit_log WHERE action = 'REFUND_STATUS'")).isEqualTo(2);
        assertThatThrownBy(() -> results.refundStatus(424242, RefundStatus.REFUNDED)).isInstanceOf(BookingNotFoundException.class);
    }

    // ---- the internal endpoints --------------------------------------------------------------------------------------

    @Test
    void theInternalEndpoints_needTheSharedKey_andValidateTheirInput() throws Exception {
        BookingView hold = aHold();
        String path = "/internal/bookings/" + hold.id() + "/payment-result";
        String ok = "{\"paymentId\":5,\"amount\":30.00,\"currency\":\"EGP\"}";

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(ok)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-Internal-Api-Key", "wrong").contentType(MediaType.APPLICATION_JSON).content(ok)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-Internal-Api-Key", INTERNAL_KEY).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/bookings/" + hold.id() + "/refund-status").header("X-Internal-Api-Key", INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"NONE\"}")).andExpect(status().isBadRequest());
        assertThat(statusOf(hold.id())).isEqualTo("PENDING_PAYMENT");

        mvc.perform(post(path).header("X-Internal-Api-Key", INTERNAL_KEY).contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("CONFIRMED"));
        mvc.perform(post("/internal/bookings/" + hold.id() + "/refund-status").header("X-Internal-Api-Key", INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REFUND_PENDING\"}")).andExpect(status().isOk());
        mvc.perform(post("/internal/bookings/424242/payment-result").header("X-Internal-Api-Key", INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(ok)).andExpect(status().isNotFound());
    }
}
