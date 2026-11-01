package com.trainbooking.payment.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Stripe is used in test mode only: a live key stops the service from starting. */
class StripeKeyGuardTest {

    @Test
    void testKeys_andBlankKeys_areAccepted() {
        assertThatCode(() -> new StripeKeyGuard("sk_test_abc", "pk_test_abc", "whsec_abc")).doesNotThrowAnyException();
        assertThatCode(() -> new StripeKeyGuard("", "", "")).doesNotThrowAnyException(); // not set up: the service starts and answers 503
        assertThatCode(() -> new StripeKeyGuard(null, null, null)).doesNotThrowAnyException();
    }

    @Test
    void aLiveSecretKey_isRefused_withoutEchoingIt() {
        assertThatThrownBy(() -> new StripeKeyGuard("sk_live_SUPERSECRETVALUE", "pk_test_abc", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STRIPE_SECRET_KEY").hasMessageNotContaining("SUPERSECRETVALUE");
    }

    @Test
    void aLivePublishableKey_orAnyOtherKind_isRefused() {
        assertThatThrownBy(() -> new StripeKeyGuard("sk_test_abc", "pk_live_PUBLICVALUE", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STRIPE_PUBLISHABLE_KEY").hasMessageNotContaining("PUBLICVALUE");
        assertThatThrownBy(() -> new StripeKeyGuard("rk_test_restricted", "", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StripeKeyGuard("pk_test_swapped", "", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StripeKeyGuard("", "sk_test_swapped", "")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theWebhookSecret_mustLookLikeOne() {
        assertThatThrownBy(() -> new StripeKeyGuard("", "", "not-a-secret")).isInstanceOf(IllegalStateException.class).hasMessageContaining("STRIPE_WEBHOOK_SECRET");
        assertThat(true).isTrue();
    }
}
