package com.trainbooking.payment.stripe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stripe is used in test mode only (a sandbox): this service refuses to start with a live key. A secret key must start with
 * {@code sk_test_} and a publishable key with {@code pk_test_}; a blank key is allowed (the service then answers 503 for anything that
 * needs Stripe). The message never contains the key itself.
 */
@Component
public class StripeKeyGuard {

    public StripeKeyGuard(@Value("${stripe.secret-key:}") String secretKey, @Value("${stripe.publishable-key:}") String publishableKey,
                          @Value("${stripe.webhook-secret:}") String webhookSecret) {
        validate(secretKey, publishableKey, webhookSecret);
    }

    static void validate(String secretKey, String publishableKey, String webhookSecret) {
        if (present(secretKey) && !secretKey.trim().startsWith("sk_test_")) {
            throw new IllegalStateException("STRIPE_SECRET_KEY must be a Stripe test-mode key (sk_test_...). A live key (or anything else) is refused.");
        }
        if (present(publishableKey) && !publishableKey.trim().startsWith("pk_test_")) {
            throw new IllegalStateException("STRIPE_PUBLISHABLE_KEY must be a Stripe test-mode key (pk_test_...). A live key (or anything else) is refused.");
        }
        if (present(webhookSecret) && !webhookSecret.trim().startsWith("whsec_")) {
            throw new IllegalStateException("STRIPE_WEBHOOK_SECRET must be a Stripe webhook signing secret (whsec_...).");
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
