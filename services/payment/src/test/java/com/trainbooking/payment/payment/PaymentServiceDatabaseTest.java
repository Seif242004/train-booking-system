package com.trainbooking.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.payment.payment.model.CancelOutcome;
import com.trainbooking.payment.payment.model.CreatePaymentRequest;
import com.trainbooking.payment.payment.model.PaymentConflictException;
import com.trainbooking.payment.payment.model.PaymentSession;
import com.trainbooking.payment.payment.service.PaymentService;
import com.trainbooking.payment.stripe.StripeCallException;
import com.trainbooking.payment.testsupport.DatabaseTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The payment of a booking against the real database with a fake Stripe: creation, the cancel-first rule of a timeout (SRS 4.2,
 * FR-P4), the race between creating a payment and the hold ending, and invalidation when the booking changes.
 */
class PaymentServiceDatabaseTest extends DatabaseTest {

    private static final long BOOKING = 7;

    @Autowired
    PaymentService payments;

    private static CreatePaymentRequest request(long booking, String amount) {
        return new CreatePaymentRequest(booking, new BigDecimal(amount), "EGP");
    }

    private String status(long booking) {
        return jdbc.sql("SELECT status FROM payment WHERE booking_id = :b").param("b", booking).query(String.class).single();
    }

    private boolean ended(long booking) {
        return jdbc.sql("SELECT ended FROM payment WHERE booking_id = :b").param("b", booking).query(Integer.class).single() == 1;
    }

    // ---- create -------------------------------------------------------------------------------------------------

    @Test
    void create_makesAnIntentForTheServerAmount_andReturnsWhatTheFormNeeds() {
        PaymentSession session = payments.create(request(BOOKING, "230.00"));

        assertThat(session.clientSecret()).isEqualTo("pi_fake_1_secret");
        assertThat(session.publishableKey()).isEqualTo("pk_test_fake");
        assertThat(session.amount()).isEqualByComparingTo("230.00");
        assertThat(stripe.createCalls()).singleElement().satisfies(call -> {
            assertThat(call.amount()).isEqualByComparingTo("230.00");
            assertThat(call.currency()).isEqualTo("EGP");
            assertThat(call.bookingId()).isEqualTo(BOOKING);
            assertThat(call.idempotencyKey()).isEqualTo("payment-7-r1"); // booking and revision: a retry is the same payment
        });
        assertThat(status(BOOKING)).isEqualTo("PENDING");
        assertThat(rows("audit_log WHERE entity = 'payment'")).isGreaterThanOrEqualTo(2); // created, intent attached
    }

    @Test
    void create_again_returnsTheSameIntent_notASecondOne() {
        PaymentSession first = payments.create(request(BOOKING, "230.00"));
        PaymentSession second = payments.create(request(BOOKING, "230.00")); // a page reload

        assertThat(second.clientSecret()).isEqualTo(first.clientSecret());
        assertThat(stripe.intentCount()).isEqualTo(1);
    }

    @Test
    void create_forAnotherAmountWhileOpen_isRefused_neverPaysAWrongAmount() {
        payments.create(request(BOOKING, "230.00"));

        assertThatThrownBy(() -> payments.create(request(BOOKING, "460.00")))
                .isInstanceOf(PaymentConflictException.class).extracting("code").isEqualTo("payment_outdated");
        assertThat(stripe.intentCount()).isEqualTo(1);
    }

    @Test
    void create_forAPaidBooking_isRefused() {
        payments.create(request(BOOKING, "230.00"));
        jdbc.sql("UPDATE payment SET status = 'SUCCEEDED' WHERE booking_id = :b").param("b", BOOKING).update(); // as the webhook leaves it

        assertThatThrownBy(() -> payments.create(request(BOOKING, "230.00")))
                .isInstanceOf(PaymentConflictException.class).extracting("code").isEqualTo("already_paid");
    }

    @Test
    void create_belowStripesMinimum_saysSo_andLeavesNoRowBehind() {
        stripe.createFails(new com.trainbooking.payment.stripe.StripeRejectedException("too small", new com.stripe.exception.InvalidRequestException(
                "Amount must convert to at least 50 cents", "amount", "req_1", "amount_too_small", 400, null)));

        assertThatThrownBy(() -> payments.create(request(BOOKING, "5.00")))
                .isInstanceOf(PaymentConflictException.class).extracting("code").isEqualTo("amount_too_small");

        assertThat(rows("payment")).isZero();
    }

    @Test
    void create_withoutAStripeKey_failsBeforeAnyRowIsWritten() {
        stripe.configured(false);

        assertThatThrownBy(() -> payments.create(request(BOOKING, "230.00")))
                .isInstanceOf(com.trainbooking.payment.stripe.StripeNotConfiguredException.class);

        assertThat(rows("payment")).isZero();
    }

    @Test
    void create_whenStripeIsDown_failsAndLeavesNothingPayable() {
        stripe.down(true);

        assertThatThrownBy(() -> payments.create(request(BOOKING, "230.00"))).isInstanceOf(StripeCallException.class);

        assertThat(status(BOOKING)).isEqualTo("CREATING"); // no intent, nobody can pay
        assertThat(stripe.intentCount()).isZero();
    }

    @Test
    void create_afterAnOutage_isRefusedWhileAnotherCreationMaybeRuns_thenTakenOverWithTheSameKey() {
        stripe.down(true);
        assertThatThrownBy(() -> payments.create(request(BOOKING, "230.00"))).isInstanceOf(StripeCallException.class);
        stripe.down(false);

        // Within the minute the row may belong to a call that is still running: wait.
        assertThatThrownBy(() -> payments.create(request(BOOKING, "230.00")))
                .isInstanceOf(PaymentConflictException.class).extracting("code").isEqualTo("payment_in_progress");

        clock.advance(Duration.ofSeconds(61)); // that call is dead by now: take over; Stripe's idempotency key makes this safe
        PaymentSession session = payments.create(request(BOOKING, "230.00"));

        assertThat(session.clientSecret()).isEqualTo("pi_fake_1_secret");
        assertThat(stripe.createCalls()).extracting(c -> c.idempotencyKey()).containsOnly("payment-7-r1");
        assertThat(status(BOOKING)).isEqualTo("PENDING");
    }

    @Test
    void create_twoAtTheSameMoment_makeOneIntent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Callable<Object> call = () -> {
                go.await();
                try {
                    return payments.create(request(BOOKING, "230.00"));
                } catch (PaymentConflictException e) {
                    return e; // the loser is told to try again, which is fine
                }
            };
            results.add(pool.submit(call));
        }
        go.countDown();
        for (Future<Object> result : results) {
            result.get();
        }
        pool.shutdown();

        assertThat(stripe.intentCount()).isEqualTo(1);
        assertThat(rows("payment WHERE booking_id = " + BOOKING)).isEqualTo(1);
    }

    // ---- cancel (the hold ended) -----------------------------------------------------------------------------------

    @Test
    void cancelPending_cancelsTheIntentAtStripe_andClosesThePaymentForGood() {
        payments.create(request(BOOKING, "230.00"));

        assertThat(payments.cancelPending(BOOKING)).isEqualTo(CancelOutcome.CANCELLED);

        assertThat(stripe.statusOf("pi_fake_1")).isEqualTo("canceled");
        assertThat(status(BOOKING)).isEqualTo("CANCELLED");
        assertThat(ended(BOOKING)).isTrue();
        assertThatThrownBy(() -> payments.create(request(BOOKING, "230.00")))
                .isInstanceOf(PaymentConflictException.class).extracting("code").isEqualTo("payment_closed");
        assertThat(rows("outbox")).isZero(); // a timeout never reports money, and never refunds
        assertThat(rows("refund")).isZero();
    }

    @Test
    void cancelPending_withNoPayment_leavesATombstone_thatRefusesALateCreation() {
        assertThat(payments.cancelPending(BOOKING)).isEqualTo(CancelOutcome.NONE);

        assertThatThrownBy(() -> payments.create(request(BOOKING, "230.00")))
                .isInstanceOf(PaymentConflictException.class).extracting("code").isEqualTo("payment_closed");
        assertThat(stripe.createCalls()).isEmpty(); // Stripe was never asked
    }

    @Test
    void cancelPending_whileAPaymentIsBeingCreated_neverLetsTheUserPay() {
        // The hold ends exactly while Stripe is creating the intent (SRS 4.2: payment at the last second).
        stripe.afterIntentCreated(call -> assertThat(payments.cancelPending(BOOKING)).isEqualTo(CancelOutcome.CANCELLED));

        assertThatThrownBy(() -> payments.create(request(BOOKING, "230.00")))
                .isInstanceOf(PaymentConflictException.class).extracting("code").isEqualTo("payment_closed");

        assertThat(stripe.statusOf("pi_fake_1")).isEqualTo("canceled"); // the intent that was made is cancelled: it cannot be paid
        assertThat(status(BOOKING)).isEqualTo("CANCELLED");
        assertThat(ended(BOOKING)).isTrue();
    }

    @Test
    void cancelPending_whenStripeIsDown_throws_andStaysClosedToNewPayments() {
        payments.create(request(BOOKING, "230.00"));
        stripe.down(true);

        assertThatThrownBy(() -> payments.cancelPending(BOOKING)).isInstanceOf(StripeCallException.class);

        assertThat(status(BOOKING)).isEqualTo("PENDING"); // not CANCELLED: the seats must stay held
        assertThat(ended(BOOKING)).isTrue();              // but nobody can start a new payment meanwhile
        stripe.down(false);
        assertThat(payments.cancelPending(BOOKING)).isEqualTo(CancelOutcome.CANCELLED); // the sweeper's next try
        assertThat(stripe.statusOf("pi_fake_1")).isEqualTo("canceled");
    }

    @Test
    void cancelPending_whenThePaymentAlreadySucceeded_thePaymentWins_andBookingIsToldOnce() {
        payments.create(request(BOOKING, "230.00"));
        stripe.setStatus("pi_fake_1", "succeeded"); // paid at the last second; the webhook has not arrived yet

        assertThat(payments.cancelPending(BOOKING)).isEqualTo(CancelOutcome.SUCCEEDED);
        assertThat(payments.cancelPending(BOOKING)).isEqualTo(CancelOutcome.SUCCEEDED); // the next sweep asks again

        assertThat(status(BOOKING)).isEqualTo("SUCCEEDED");
        assertThat(rows("outbox WHERE kind = 'PAYMENT_RESULT' AND booking_id = " + BOOKING)).isEqualTo(1);
        assertThat(rows("refund")).isZero(); // a timeout never refunds
    }

    @Test
    void cancelPending_whileStripeIsProcessing_keepsTheSeatsHeld_andAsksAgain() {
        payments.create(request(BOOKING, "230.00"));
        stripe.setStatus("pi_fake_1", "processing");

        assertThat(payments.cancelPending(BOOKING)).isEqualTo(CancelOutcome.PROCESSING);

        assertThat(status(BOOKING)).isEqualTo("PROCESSING");
        assertThat(stripe.statusOf("pi_fake_1")).isEqualTo("processing"); // not cancelled
        stripe.setStatus("pi_fake_1", "succeeded");
        assertThat(payments.cancelPending(BOOKING)).isEqualTo(CancelOutcome.SUCCEEDED); // Stripe settled
    }

    // ---- invalidate (the booking changes) ---------------------------------------------------------------------------

    @Test
    void invalidate_cancelsTheIntent_butAllowsANewPaymentForTheNewAmount() {
        payments.create(request(BOOKING, "230.00"));

        assertThat(payments.invalidate(BOOKING)).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(ended(BOOKING)).isFalse();

        PaymentSession second = payments.create(request(BOOKING, "460.00")); // the user picked another seat
        assertThat(second.clientSecret()).isEqualTo("pi_fake_2_secret");
        assertThat(stripe.statusOf("pi_fake_1")).isEqualTo("canceled"); // the old amount can never be paid
        assertThat(stripe.createCalls()).extracting(c -> c.idempotencyKey()).containsExactly("payment-7-r1", "payment-7-r2");
        assertThat(jdbc.sql("SELECT amount FROM payment WHERE booking_id = :b").param("b", BOOKING).query(BigDecimal.class).single())
                .isEqualByComparingTo("460.00");
    }

    @Test
    void twoInvalidatesAtOnce_withANewPaymentCreatedBetween_neverLeaveTheNewPaymentPayableAfterTheHoldEnds() {
        payments.create(request(BOOKING, "230.00")); // intent 1
        boolean[] done = {false};
        stripe.beforeCancel(intentId -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            // While the first invalidate is asking Stripe about intent 1, another request invalidates it too and the user opens the
            // payment page for the new amount: intent 2 becomes the live payment.
            assertThat(payments.invalidate(BOOKING)).isEqualTo(CancelOutcome.CANCELLED);
            payments.create(request(BOOKING, "460.00"));
        });

        CancelOutcome outcome = payments.invalidate(BOOKING);

        // The first invalidate must not mark the NEW payment cancelled without cancelling it at Stripe: it plans again against intent 2.
        assertThat(outcome).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(stripe.statusOf("pi_fake_1")).isEqualTo("canceled");
        assertThat(stripe.statusOf("pi_fake_2")).isEqualTo("canceled");
        assertThat(status(BOOKING)).isEqualTo("CANCELLED");
    }

    @Test
    void aSlowFirstCreator_doesNotCancelTheIntentThatATakeoverAlreadyAttached() {
        stripe.afterIntentCreated(call -> {
            // The first creator is stuck for over a minute; a second request takes the row over (same idempotency key, same intent).
            clock.advance(Duration.ofSeconds(61));
            stripe.afterIntentCreated(c -> {
            });
            PaymentSession second = payments.create(request(BOOKING, "230.00"));
            assertThat(second.clientSecret()).isEqualTo("pi_fake_1_secret");
        });

        PaymentSession first = payments.create(request(BOOKING, "230.00"));

        assertThat(first.clientSecret()).isEqualTo("pi_fake_1_secret");
        assertThat(stripe.statusOf("pi_fake_1")).isEqualTo("requires_payment_method"); // still payable: the user was given its secret
        assertThat(status(BOOKING)).isEqualTo("PENDING");
        assertThat(stripe.intentCount()).isEqualTo(1);
    }

    @Test
    void invalidate_whenAlreadyPaid_saysSo_soTheBookingMustNotChange() {
        payments.create(request(BOOKING, "230.00"));
        stripe.setStatus("pi_fake_1", "succeeded");

        assertThat(payments.invalidate(BOOKING)).isEqualTo(CancelOutcome.SUCCEEDED);
    }

    @Test
    void invalidate_withNoPayment_doesNothing() {
        assertThat(payments.invalidate(BOOKING)).isEqualTo(CancelOutcome.NONE);
        assertThat(rows("payment")).isZero(); // no tombstone: the hold has not ended
    }

    @Test
    void invalidate_whileACreationIsInFlight_stopsItFromHandingOutTheSecret() {
        stripe.afterIntentCreated(call -> assertThat(payments.invalidate(BOOKING)).isEqualTo(CancelOutcome.CANCELLED));

        assertThatThrownBy(() -> payments.create(request(BOOKING, "230.00")))
                .isInstanceOf(PaymentConflictException.class).extracting("code").isEqualTo("payment_closed");
        assertThat(stripe.statusOf("pi_fake_1")).isEqualTo("canceled");

        stripe.afterIntentCreated(call -> {
        });
        PaymentSession again = payments.create(request(BOOKING, "460.00")); // the booking changed: a fresh payment is allowed
        assertThat(again.clientSecret()).isEqualTo("pi_fake_2_secret");
    }
}
