package com.trainbooking.payment.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.StripeClient;
import com.stripe.param.PaymentIntentConfirmParams;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The real Stripe, in test mode. Skipped (and shown as SKIPPED) unless STRIPE_SECRET_KEY, a test key (sk_test_...), is set in the
 * environment or in services/payment/.env. It proves the things the fake can only claim: that cancelling an unpaid intent works,
 * that cancelling a paid one reports "succeeded" instead of failing (the payment wins), that an idempotency key returns the same
 * intent, and that a full refund works.
 */
class LiveStripeTest {

    static boolean testKeyPresent() {
        return secretKey().startsWith("sk_test_");
    }

    static String secretKey() {
        String fromEnvironment = System.getenv("STRIPE_SECRET_KEY");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        try {
            Path file = Path.of(".env");
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file)) {
                    if (line.startsWith("STRIPE_SECRET_KEY=")) {
                        return line.substring("STRIPE_SECRET_KEY=".length()).trim();
                    }
                }
            }
        } catch (IOException e) {
            // no key
        }
        return "";
    }

    @Test
    @EnabledIf("testKeyPresent")
    void anUnpaidIntent_isCancelled_andIdempotencyKeysReturnTheSameIntent() {
        StripeGateway stripe = new StripeApiGateway(secretKey());
        String key = "live-test-" + UUID.randomUUID();

        StripeGateway.Intent intent = stripe.createIntent(new BigDecimal("5.00"), "EGP", 999_001, key);
        StripeGateway.Intent again = stripe.createIntent(new BigDecimal("5.00"), "EGP", 999_001, key);

        assertThat(intent.status()).isEqualTo("requires_payment_method");
        assertThat(intent.clientSecret()).startsWith("pi_");
        assertThat(again.id()).isEqualTo(intent.id());

        assertThat(stripe.cancelIntent(intent.id()).status()).isEqualTo("canceled");
        assertThat(stripe.cancelIntent(intent.id()).status()).isEqualTo("canceled"); // cancelling again reports the state, no error
    }

    /** Stripe's test cards, as payment-method tokens: 4242 4242 4242 4242 (success), 4000 0000 0000 0002 (declined), 4000 0000 0000 3220 (3D Secure). */
    @Test
    @EnabledIf("testKeyPresent")
    void aDeclinedCard_leavesTheIntentOpenAndCancellable_andAThreeDSecureCardNeedsActionOnThePage() throws Exception {
        String key = secretKey();
        StripeGateway stripe = new StripeApiGateway(key);
        StripeClient raw = new StripeClient(key);

        StripeGateway.Intent declined = stripe.createIntent(new BigDecimal("5.00"), "EGP", 999_003, "live-test-" + UUID.randomUUID());
        try {
            raw.v1().paymentIntents().confirm(declined.id(), PaymentIntentConfirmParams.builder()
                    .setPaymentMethod("pm_card_chargeDeclined").setReturnUrl("https://example.com/return").build());
        } catch (com.stripe.exception.CardException expected) {
            assertThat(expected.getCode()).isEqualTo("card_declined"); // 4000 0000 0000 0002
        }
        assertThat(stripe.retrieveIntent(declined.id()).status()).isEqualTo("requires_payment_method"); // the user can try again
        assertThat(stripe.cancelIntent(declined.id()).status()).isEqualTo("canceled");

        StripeGateway.Intent threeDs = stripe.createIntent(new BigDecimal("5.00"), "EGP", 999_004, "live-test-" + UUID.randomUUID());
        raw.v1().paymentIntents().confirm(threeDs.id(), PaymentIntentConfirmParams.builder()
                .setPaymentMethod("pm_card_threeDSecure2Required").setReturnUrl("https://example.com/return").build()); // 4000 0000 0000 3220
        assertThat(stripe.retrieveIntent(threeDs.id()).status()).isEqualTo("requires_action"); // the customer must complete the challenge
        assertThat(stripe.cancelIntent(threeDs.id()).status()).isEqualTo("canceled"); // and an unfinished challenge can still be cancelled
    }

    @Test
    @EnabledIf("testKeyPresent")
    void aPaidIntent_cannotBeCancelled_theCancelReportsSucceeded_andAFullRefundWorks() throws Exception {
        String key = secretKey();
        StripeGateway stripe = new StripeApiGateway(key);
        StripeGateway.Intent intent = stripe.createIntent(new BigDecimal("5.00"), "EGP", 999_002, "live-test-" + UUID.randomUUID());

        // The customer pays with Stripe's test card.
        new StripeClient(key).v1().paymentIntents().confirm(intent.id(), PaymentIntentConfirmParams.builder()
                .setPaymentMethod("pm_card_visa").setReturnUrl("https://example.com/return").build());

        assertThat(stripe.cancelIntent(intent.id()).status()).isEqualTo("succeeded"); // too late to cancel: the payment wins

        String refundKey = "live-test-refund-" + UUID.randomUUID();
        StripeGateway.RefundResult refund = stripe.createRefund(intent.id(), new BigDecimal("5.00"), "EGP", refundKey);
        assertThat(refund.status()).isIn("succeeded", "pending");
        assertThat(stripe.createRefund(intent.id(), new BigDecimal("5.00"), "EGP", refundKey).id()).isEqualTo(refund.id()); // never twice
    }
}
