package com.trainbooking.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.payment.payment.model.CreatePaymentRequest;
import com.trainbooking.payment.payment.service.PaymentService;
import com.trainbooking.payment.testsupport.DatabaseTest;
import com.trainbooking.payment.testsupport.Events;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Stripe webhook (SRS 9.3, FR-P3, FR-P6): nothing is believed before its signature is verified, and an event is handled at most
 * once. The payloads are signed here with the real algorithm, so these tests also pin the signature format.
 */
class WebhookDatabaseTest extends DatabaseTest {

    private static final long BOOKING = 7;

    @Autowired
    PaymentService payments;

    /** A payment with an open intent {@code pi_fake_1}. */
    private void openPayment() {
        payments.create(new CreatePaymentRequest(BOOKING, new BigDecimal("230.00"), "EGP"));
    }

    private String paymentStatus() {
        return jdbc.sql("SELECT status FROM payment WHERE booking_id = :b").param("b", BOOKING).query(String.class).single();
    }

    // ---- the signature ----------------------------------------------------------------------------------------

    @Test
    void anEventWithoutASignature_isRefused_andNothingChanges() throws Exception {
        openPayment();
        String payload = Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_fake_1", "succeeded");

        deliver(payload, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_signature"));

        assertThat(paymentStatus()).isEqualTo("PENDING");
        assertThat(rows("webhook_event")).isZero();
        assertThat(rows("outbox")).isZero();
    }

    @Test
    void anEventSignedWithAnotherSecret_isRefused() throws Exception {
        openPayment();
        String payload = Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_fake_1", "succeeded");

        deliver(payload, signature(payload, "whsec_someone_else", clock.instant())).andExpect(status().isBadRequest());

        assertThat(paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    void aTamperedBody_isRefused() throws Exception {
        openPayment();
        String payload = Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_fake_1", "succeeded");
        String signature = signature(payload, WEBHOOK_SECRET, clock.instant());

        deliver(payload.replace("23000", "1"), signature).andExpect(status().isBadRequest());

        assertThat(paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    void anOldSignature_isRefused_soARecordedEventCannotBeReplayedLater() throws Exception {
        openPayment();
        String payload = Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_fake_1", "succeeded");
        String signature = signature(payload, WEBHOOK_SECRET, clock.instant().minus(Duration.ofMinutes(10)));

        deliver(payload, signature).andExpect(status().isBadRequest());

        assertThat(paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    void aBodyThatIsNotAnEvent_isRefused() throws Exception {
        deliver("not json at all").andExpect(status().isBadRequest());
        deliver("{}").andExpect(status().isBadRequest());
    }

    // ---- the effects ---------------------------------------------------------------------------------------------

    @Test
    void paymentSucceeded_marksThePaid_andQueuesTheNewsForBooking() throws Exception {
        openPayment();

        deliver(Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_fake_1", "succeeded"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("HANDLED"));

        assertThat(paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(rows("outbox WHERE kind = 'PAYMENT_RESULT' AND booking_id = " + BOOKING + " AND status = 'PENDING'")).isEqualTo(1);
        assertThat(rows("webhook_event WHERE stripe_event_id = 'evt_1'")).isEqualTo(1);
    }

    @Test
    void theSameEventTwice_isHandledOnce() throws Exception {
        openPayment();
        String payload = Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_fake_1", "succeeded");

        deliver(payload).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("HANDLED"));
        deliver(payload).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE")); // Stripe retried; we acknowledge

        assertThat(rows("outbox")).isEqualTo(1);
        assertThat(rows("webhook_event")).isEqualTo(1);
    }

    @Test
    void twoDeliveriesAtTheSameMoment_areHandledOnce() throws Exception {
        openPayment();
        String payload = Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_fake_1", "succeeded");
        String signature = signature(payload, WEBHOOK_SECRET, clock.instant());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                deliver(payload, signature).andExpect(status().isOk());
                return null;
            }));
        }
        go.countDown();
        for (var future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(rows("outbox")).isEqualTo(1);
        assertThat(rows("webhook_event")).isEqualTo(1);
    }

    @Test
    void aDifferentEventForThePaidPayment_doesNotQueueTheNewsAgain() throws Exception {
        openPayment();
        deliver(Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_fake_1", "succeeded")).andExpect(status().isOk());
        deliver(Events.paymentIntent("evt_2", "payment_intent.succeeded", "pi_fake_1", "succeeded")).andExpect(status().isOk());

        assertThat(rows("outbox")).isEqualTo(1);
    }

    @Test
    void aFailedAttempt_keepsThePaymentOpenForARetry_andOnlyNotesTheReason() throws Exception {
        openPayment();

        deliver(Events.paymentIntent("evt_1", "payment_intent.payment_failed", "pi_fake_1", "requires_payment_method", "Your card was declined."))
                .andExpect(status().isOk());

        assertThat(paymentStatus()).isEqualTo("PENDING"); // FR-P5: the hold and the intent stay, the user can try again
        assertThat(jdbc.sql("SELECT last_error FROM payment WHERE booking_id = :b").param("b", BOOKING).query(String.class).single())
                .isEqualTo("Your card was declined.");
        assertThat(rows("outbox")).isZero();
    }

    @Test
    void processingThenSucceeded_followsStripe() throws Exception {
        openPayment();

        deliver(Events.paymentIntent("evt_1", "payment_intent.processing", "pi_fake_1", "processing")).andExpect(status().isOk());
        assertThat(paymentStatus()).isEqualTo("PROCESSING");

        deliver(Events.paymentIntent("evt_2", "payment_intent.succeeded", "pi_fake_1", "succeeded")).andExpect(status().isOk());
        assertThat(paymentStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aSuccessThatArrivesAfterTheCancel_stillWins_theMoneyIsTaken() throws Exception {
        openPayment();
        stripe.setStatus("pi_fake_1", "succeeded");
        // The customer paid at the last second; our cancel had already marked the row CANCELLED.
        jdbc.sql("UPDATE payment SET status = 'CANCELLED', ended = 1").update();

        deliver(Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_fake_1", "succeeded")).andExpect(status().isOk());

        assertThat(paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(rows("outbox WHERE kind = 'PAYMENT_RESULT'")).isEqualTo(1); // Booking confirms it, or says it must be refunded
    }

    @Test
    void anUnknownIntent_andAnUnwantedEventType_areAcknowledgedAndIgnored() throws Exception {
        deliver(Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_nobody", "succeeded"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("IGNORED"));
        deliver(Events.other("evt_2", "customer.created"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("IGNORED"));

        assertThat(rows("outbox")).isZero();
    }

    @Test
    void theWebhookNeedsNoLogin_butEveryOtherRouteIsClosed() throws Exception {
        // No Authorization header was sent in any test above: Stripe cannot send one. The signature is what authenticates.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/webhooks/payment")).andExpect(status().isMethodNotAllowed());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/anything-else")).andExpect(status().is4xxClientError());
    }
}
