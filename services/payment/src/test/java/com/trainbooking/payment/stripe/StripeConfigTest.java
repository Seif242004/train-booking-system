package com.trainbooking.payment.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StripeConfigTest {

    @Test
    void withoutASecretKey_everyStripeCallIsRefusedClearly_thereIsNoFakeStripe() {
        StripeGateway gateway = new StripeConfig().stripeGateway("");

        assertThatThrownBy(() -> gateway.createIntent(BigDecimal.TEN, "EGP", 1, "k")).isInstanceOf(StripeNotConfiguredException.class)
                .hasMessageContaining("STRIPE_SECRET_KEY");
        assertThatThrownBy(() -> gateway.cancelIntent("pi_1")).isInstanceOf(StripeNotConfiguredException.class);
        assertThatThrownBy(() -> gateway.createRefund("pi_1", BigDecimal.TEN, "EGP", "k")).isInstanceOf(StripeNotConfiguredException.class);
        assertThat(new StripeConfig().stripeGateway("   ")).isInstanceOf(StripeConfig.NotConfigured.class);
    }

    @Test
    void amountsGoToStripeInPiastres_exactly() {
        assertThat(StripeApiGateway.minorUnits(new BigDecimal("230.00"))).isEqualTo(23000);
        assertThat(StripeApiGateway.minorUnits(new BigDecimal("0.01"))).isEqualTo(1);
        assertThat(StripeApiGateway.minorUnits(new BigDecimal("150.5"))).isEqualTo(15050);
        assertThat(StripeApiGateway.minorUnits(new BigDecimal("760"))).isEqualTo(76000);
        assertThatThrownBy(() -> StripeApiGateway.minorUnits(new BigDecimal("10.001"))).isInstanceOf(ArithmeticException.class); // never rounded silently
    }
}
