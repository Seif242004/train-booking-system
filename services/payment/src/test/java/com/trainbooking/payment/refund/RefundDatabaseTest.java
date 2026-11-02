package com.trainbooking.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.payment.payment.model.CreatePaymentRequest;
import com.trainbooking.payment.payment.service.PaymentService;
import com.trainbooking.payment.refund.model.NoPaymentToRefundException;
import com.trainbooking.payment.refund.model.Refund;
import com.trainbooking.payment.refund.service.RefundRetrier;
import com.trainbooking.payment.refund.service.RefundService;
import com.trainbooking.payment.testsupport.DatabaseTest;
import com.trainbooking.payment.testsupport.Events;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Full refunds (SRS 7.2): exactly one per booking, never doubled by a retry, and only for money that was really taken. */
class RefundDatabaseTest extends DatabaseTest {

    private static final long BOOKING = 7;

    @Autowired
    PaymentService payments;
    @Autowired
    RefundService refunds;
    @Autowired
    RefundRetrier retrier;

    /** A paid booking: an intent that succeeded and was reported by the webhook. */
    private void paidBooking() throws Exception {
        payments.create(new CreatePaymentRequest(BOOKING, new BigDecimal("230.00"), "EGP"));
        stripe.setStatus("pi_fake_1", "succeeded");
        deliver(Events.paymentIntent("evt_pay", "payment_intent.succeeded", "pi_fake_1", "succeeded")).andExpect(status().isOk());
    }

    private List<String> outboxStatuses() {
        return jdbc.sql("SELECT payload FROM outbox WHERE kind = 'REFUND_STATUS' ORDER BY id").query(String.class).list();
    }

    @Test
    void aRefund_takesTheWholePayment_once_andTellsBookingPendingThenRefunded() throws Exception {
        paidBooking();

        Refund refund = refunds.request(BOOKING, "Cancelled by the passenger");

        assertThat(stripe.refundCalls()).singleElement().satisfies(call -> {
            assertThat(call.intentId()).isEqualTo("pi_fake_1");
            assertThat(call.amount()).isEqualByComparingTo("230.00"); // the full amount, as stored on the payment
            assertThat(call.idempotencyKey()).isEqualTo("refund-" + refund.id());
        });
        assertThat(jdbc.sql("SELECT status FROM refund WHERE booking_id = :b").param("b", BOOKING).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(outboxStatuses()).containsExactly("{\"status\":\"REFUND_PENDING\"}", "{\"status\":\"REFUNDED\"}");
    }

    @Test
    void askingTwice_isTheSameRefund_andStripeIsAskedOnce() throws Exception {
        paidBooking();

        Refund first = refunds.request(BOOKING, "Cancelled by the passenger");
        Refund second = refunds.request(BOOKING, "Cancelled by an admin");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(stripe.refundCalls()).hasSize(1);
        assertThat(rows("refund")).isEqualTo(1);
    }

    @Test
    void noRefundForMoneyThatWasNeverTaken() throws Exception {
        assertThatThrownBy(() -> refunds.request(BOOKING, "x")).isInstanceOf(NoPaymentToRefundException.class); // no payment at all

        payments.create(new CreatePaymentRequest(BOOKING, new BigDecimal("230.00"), "EGP"));
        assertThatThrownBy(() -> refunds.request(BOOKING, "x")).isInstanceOf(NoPaymentToRefundException.class); // an open intent, nobody paid

        assertThat(stripe.refundCalls()).isEmpty();
    }

    @Test
    void whenStripeIsDown_theRefundWaits_andIsRetriedWithoutDoubling() throws Exception {
        paidBooking();
        stripe.down(true);

        Refund waiting = refunds.request(BOOKING, "Cancelled");
        assertThat(waiting.status().name()).isEqualTo("PENDING");
        assertThat(outboxStatuses()).containsExactly("{\"status\":\"REFUND_PENDING\"}");

        assertThat(retrier.retryDue()).isZero();   // backoff: not yet
        clock.advance(Duration.ofSeconds(120));
        assertThat(retrier.retryDue()).isEqualTo(1); // Stripe still down: tried, failed, rescheduled
        assertThat(stripe.refundCalls()).isEmpty();

        stripe.down(false);
        clock.advance(Duration.ofSeconds(120));
        assertThat(retrier.retryDue()).isEqualTo(1);

        assertThat(stripe.refundCalls()).singleElement().extracting(c -> c.idempotencyKey()).isEqualTo("refund-" + waiting.id());
        assertThat(outboxStatuses()).containsExactly("{\"status\":\"REFUND_PENDING\"}", "{\"status\":\"REFUNDED\"}");
        clock.advance(Duration.ofSeconds(120));
        assertThat(retrier.retryDue()).isZero(); // done: not asked again
    }

    @Test
    void aPendingRefund_isFinishedByTheWebhook_andLaterReportsChangeNothing() throws Exception {
        paidBooking();
        stripe.refundStatus("pending");

        refunds.request(BOOKING, "Cancelled");
        assertThat(outboxStatuses()).containsExactly("{\"status\":\"REFUND_PENDING\"}");

        deliver(Events.refund("evt_r1", "refund.updated", "re_fake_2", "pi_fake_1", "succeeded")).andExpect(status().isOk());
        assertThat(outboxStatuses()).containsExactly("{\"status\":\"REFUND_PENDING\"}", "{\"status\":\"REFUNDED\"}");

        deliver(Events.refund("evt_r2", "refund.failed", "re_fake_2", "pi_fake_1", "failed")).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT status FROM refund").query(String.class).single()).isEqualTo("SUCCEEDED"); // final stays final
        assertThat(outboxStatuses()).hasSize(2);
    }

    @Test
    void aRefundStripeRejects_isFlaggedAsFailed() throws Exception {
        paidBooking();
        stripe.refundStatus("failed");

        refunds.request(BOOKING, "Cancelled");

        assertThat(outboxStatuses()).containsExactly("{\"status\":\"REFUND_PENDING\"}", "{\"status\":\"REFUND_FAILED\"}");
        assertThat(jdbc.sql("SELECT failure_reason FROM refund").query(String.class).single()).isEqualTo("declined");
    }

    @Test
    void aRefundThatIsBeingSent_isNotPickedUpAndSentAgainByTheRetrier() throws Exception {
        paidBooking();
        int[] retried = {-1};
        // While the request is talking to Stripe, the retrier runs (it runs every second in the real service).
        stripe.whileSendingRefund(() -> retried[0] = retrier.retryDue());

        refunds.request(BOOKING, "Cancelled");

        assertThat(retried[0]).isZero();
        assertThat(stripe.refundCalls()).hasSize(1); // one call with the idempotency key, not two at once
        assertThat(jdbc.sql("SELECT status FROM refund").query(String.class).single()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aRefundStripeRefusesForGood_isFlaggedFailedAtOnce_notRetriedForever() throws Exception {
        paidBooking();
        stripe.rejectRefunds(true); // for example charge_already_refunded

        refunds.request(BOOKING, "Cancelled");

        assertThat(jdbc.sql("SELECT status FROM refund").query(String.class).single()).isEqualTo("FAILED");
        assertThat(outboxStatuses()).containsExactly("{\"status\":\"REFUND_PENDING\"}", "{\"status\":\"REFUND_FAILED\"}");
        clock.advance(Duration.ofMinutes(5));
        assertThat(retrier.retryDue()).isZero();
        assertThat(stripe.refundCalls()).hasSize(1);
    }

    @Test
    void aRefundThatCannotBeSentForAnHour_isFlaggedFailedForAnAdmin() throws Exception {
        paidBooking();
        stripe.down(true);
        refunds.request(BOOKING, "Cancelled");

        for (int round = 0; round < 70; round++) {
            clock.advance(Duration.ofSeconds(120));
            retrier.retryDue();
        }

        assertThat(jdbc.sql("SELECT status FROM refund").query(String.class).single()).isEqualTo("FAILED");
        assertThat(outboxStatuses()).last().isEqualTo("{\"status\":\"REFUND_FAILED\"}");
    }

    @Test
    void aWebhookFasterThanTheAnswerOfOurOwnCall_isMatchedThroughThePaymentIntent() throws Exception {
        paidBooking();
        stripe.down(true);
        Refund refund = refunds.request(BOOKING, "Cancelled"); // no Stripe refund id stored yet

        deliver(Events.refund("evt_r1", "refund.updated", "re_from_dashboard_1", "pi_fake_1", "succeeded")).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT stripe_refund_id FROM refund WHERE id = :id").param("id", refund.id()).query(String.class).single())
                .isEqualTo("re_from_dashboard_1");
        assertThat(outboxStatuses()).containsExactly("{\"status\":\"REFUND_PENDING\"}", "{\"status\":\"REFUNDED\"}");
    }

    @Test
    void theInternalEndpoint_answersWithTheBookingStatusNames() throws Exception {
        paidBooking();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/refunds")
                        .header("X-Internal-Api-Key", INTERNAL_KEY).contentType("application/json").content("{\"bookingId\":7,\"reason\":\"Cancelled\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("REFUNDED"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/refunds")
                        .header("X-Internal-Api-Key", INTERNAL_KEY).contentType("application/json").content("{\"bookingId\":99}"))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("no_payment_to_refund"));
    }
}
