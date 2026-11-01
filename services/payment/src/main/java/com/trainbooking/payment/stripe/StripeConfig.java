package com.trainbooking.payment.stripe;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the Stripe gateway from the settings. Without a secret key the service still starts (so the rest of the system can run), but
 * every call that needs Stripe is answered with 503 {@code stripe_not_configured}. There is no fake Stripe in the running service.
 */
@Configuration
public class StripeConfig {

    @Bean
    @ConditionalOnMissingBean(StripeGateway.class)
    StripeGateway stripeGateway(@Value("${stripe.secret-key:}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            return new NotConfigured();
        }
        return new StripeApiGateway(secretKey);
    }

    /** Refuses everything, clearly. */
    static final class NotConfigured implements StripeGateway {

        @Override
        public boolean configured() {
            return false;
        }

        private static StripeNotConfiguredException refuse() {
            return new StripeNotConfiguredException("STRIPE_SECRET_KEY");
        }

        @Override
        public Intent createIntent(BigDecimal amount, String currency, long bookingId, String idempotencyKey) {
            throw refuse();
        }

        @Override
        public Intent retrieveIntent(String intentId) {
            throw refuse();
        }

        @Override
        public Intent cancelIntent(String intentId) {
            throw refuse();
        }

        @Override
        public RefundResult createRefund(String intentId, BigDecimal amount, String currency, String idempotencyKey) {
            throw refuse();
        }
    }
}
