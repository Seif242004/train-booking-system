package com.trainbooking.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.payment.payment.model.CreatePaymentRequest;
import com.trainbooking.payment.payment.service.PaymentService;
import com.trainbooking.payment.stripe.StripeNotConfiguredException;
import com.trainbooking.payment.testsupport.DatabaseTest;
import com.trainbooking.payment.testsupport.DatabaseTestConfig;
import com.trainbooking.payment.testsupport.RequiresTestDatabase;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** A deployment with a secret key but no publishable key cannot show the form: no intent is made for nothing. */
@SpringBootTest(properties = {
        "trainbooking.internal-api-key=" + DatabaseTest.INTERNAL_KEY,
        "trainbooking.outbox.enabled=false",
        "stripe.publishable-key=",
        "stripe.webhook-secret=" + DatabaseTest.WEBHOOK_SECRET
})
@AutoConfigureMockMvc
@Import(DatabaseTestConfig.class)
@RequiresTestDatabase
class PaymentWithoutPublishableKeyDatabaseTest extends DatabaseTest {

    @Autowired
    PaymentService payments;

    @Test
    void create_failsBeforeStripeOrTheDatabaseAreTouched() {
        assertThatThrownBy(() -> payments.create(new CreatePaymentRequest(7L, new BigDecimal("230.00"), "EGP")))
                .isInstanceOf(StripeNotConfiguredException.class).hasMessageContaining("STRIPE_PUBLISHABLE_KEY");

        assertThat(stripe.intentCount()).isZero();
        assertThat(rows("payment")).isZero();
    }
}
