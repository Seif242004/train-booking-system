package com.trainbooking.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.payment.outbox.service.BookingNotifier;
import com.trainbooking.payment.outbox.service.OutboxDispatcher;
import com.trainbooking.payment.payment.model.CreatePaymentRequest;
import com.trainbooking.payment.payment.service.PaymentService;
import com.trainbooking.payment.testsupport.DatabaseTest;
import com.trainbooking.payment.testsupport.Events;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The delivery of payments and refunds to Booking and Seat (CLAUDE.md, outbox + retry poller): a payment that succeeded is never
 * lost because Booking was down, and the news of one booking arrives in order.
 */
class OutboxDatabaseTest extends DatabaseTest {

    @Autowired
    PaymentService payments;
    @Autowired
    OutboxDispatcher dispatcher;

    private void pay(long booking, String intent, String eventId) throws Exception {
        payments.create(new CreatePaymentRequest(booking, new BigDecimal("230.00"), "EGP"));
        deliver(Events.paymentIntent(eventId, "payment_intent.succeeded", intent, "succeeded")).andExpect(status().isOk());
    }

    private int pending() {
        return rows("outbox WHERE status = 'PENDING'");
    }

    @Test
    void aSucceededPayment_isDeliveredToBooking_withTheAmount_andMarkedSent() throws Exception {
        pay(7, "pi_fake_1", "evt_1");

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        assertThat(booking.calls()).singleElement().satisfies(call -> {
            assertThat(call.kind()).isEqualTo("payment-result");
            assertThat(call.bookingId()).isEqualTo(7);
            assertThat(call.payload()).contains("\"amount\":230").contains("\"currency\":\"EGP\"");
        });
        assertThat(rows("outbox WHERE status = 'SENT'")).isEqualTo(1);
        assertThat(dispatcher.dispatchDue()).isZero(); // never delivered twice
    }

    @Test
    void whenBookingIsDown_thePaymentWaits_withABackoff_andArrivesLater() throws Exception {
        pay(7, "pi_fake_1", "evt_1");
        booking.down(true);

        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(pending()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT attempts FROM outbox").query(Integer.class).single()).isEqualTo(1);

        booking.down(false);
        assertThat(dispatcher.dispatchDue()).isZero();      // backoff: not before the next time
        clock.advance(Duration.ofSeconds(30));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);  // Booking is back: the payment is not lost
        assertThat(pending()).isZero();
    }

    @Test
    void theBackoffGrowsAndIsCapped() throws Exception {
        pay(7, "pi_fake_1", "evt_1");
        booking.down(true);

        long previous = 0;
        for (int attempt = 1; attempt <= 8; attempt++) {
            clock.advance(Duration.ofSeconds(120));
            dispatcher.dispatchDue();
            long wait = jdbc.sql("SELECT EXTRACT(DAY FROM (next_attempt_at - TIMESTAMP '2026-11-01 10:00:00 +00:00')) * 86400 "
                    + "+ EXTRACT(HOUR FROM (next_attempt_at - TIMESTAMP '2026-11-01 10:00:00 +00:00')) * 3600 "
                    + "+ EXTRACT(MINUTE FROM (next_attempt_at - TIMESTAMP '2026-11-01 10:00:00 +00:00')) * 60 "
                    + "+ EXTRACT(SECOND FROM (next_attempt_at - TIMESTAMP '2026-11-01 10:00:00 +00:00')) FROM outbox").query(Long.class).single()
                    - 120L * attempt;
            assertThat(wait).isBetween(previous, 60L);
            previous = wait;
        }
        assertThat(previous).isEqualTo(60); // 2, 4, 8, 16, 32, 60, 60...
    }

    @Test
    void theNewsOfOneBooking_goesOutInOrder_evenWhenTheFirstOneFailed() throws Exception {
        pay(7, "pi_fake_1", "evt_1");
        jdbc.sql("INSERT INTO outbox (kind, booking_id, payload, status, attempts, next_attempt_at, created_at) "
                + "VALUES ('REFUND_STATUS', 7, '{\"status\":\"REFUND_PENDING\"}', 'PENDING', 0, TIMESTAMP '2026-11-01 09:00:00 +00:00', SYSTIMESTAMP)").update();
        booking.down(true);
        dispatcher.dispatchDue(); // the payment result fails and backs off

        booking.down(false);
        // The refund news is due, but must not overtake the payment result that is still waiting.
        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(booking.calls()).isEmpty();

        clock.advance(Duration.ofSeconds(30));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);  // payment result
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);  // then the refund status
        assertThat(booking.calls()).extracting(c -> c.kind()).containsExactly("payment-result", "refund-status");
    }

    @Test
    void whenBookingCannotTakeThePayment_itIsRefunded_once() throws Exception {
        pay(7, "pi_fake_1", "evt_1");
        stripe.setStatus("pi_fake_1", "succeeded");
        booking.answers(BookingNotifier.PaymentAnswer.REFUND_REQUIRED); // the hold is gone, or the amount differs

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        assertThat(stripe.refundCalls()).hasSize(1);
        assertThat(rows("refund")).isEqualTo(1);
        assertThat(dispatcher.dispatchDue()).isEqualTo(1); // the refund status news (REFUND_PENDING, then REFUNDED) follows
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(booking.calls()).extracting(c -> c.kind()).containsExactly("payment-result", "refund-status", "refund-status");
        assertThat(stripe.refundCalls()).hasSize(1);
    }

    @Test
    void aPaymentBookingRefusesForGood_isRefunded_notLeftWithNeitherABookingNorARefund() throws Exception {
        pay(7, "pi_fake_1", "evt_1");
        booking.rejecting(true); // Booking answers 404/400: it will never accept this message

        assertThat(dispatcher.dispatchDue()).isZero();

        assertThat(rows("outbox WHERE kind = 'PAYMENT_RESULT' AND status = 'FAILED'")).isEqualTo(1); // logged as an error, never retried forever
        assertThat(stripe.refundCalls()).hasSize(1); // the money taken goes back
        assertThat(rows("refund WHERE booking_id = 7")).isEqualTo(1);
        booking.rejecting(false);
        pay(8, "pi_fake_2", "evt_2");
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
    }
}
