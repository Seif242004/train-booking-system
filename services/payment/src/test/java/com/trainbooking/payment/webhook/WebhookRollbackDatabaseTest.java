package com.trainbooking.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.payment.payment.model.PaymentStatus;
import com.trainbooking.payment.payment.repository.PaymentRepository;
import com.trainbooking.payment.payment.model.CreatePaymentRequest;
import com.trainbooking.payment.payment.service.PaymentService;
import com.trainbooking.payment.testsupport.DatabaseTest;
import com.trainbooking.payment.testsupport.Events;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * An event whose handling fails must not be recorded as handled: the record of the event and its effect are one transaction, so
 * Stripe's retry processes the event again instead of being told "already done" for something that never happened.
 */
class WebhookRollbackDatabaseTest extends DatabaseTest {

    @Autowired
    PaymentService payments;

    @MockitoSpyBean
    PaymentRepository paymentRows;

    @Test
    void aFailureWhileHandling_rollsBackTheEventRecordToo_soTheRetryWorks() throws Exception {
        payments.create(new CreatePaymentRequest(7L, new BigDecimal("230.00"), "EGP"));
        String payload = Events.paymentIntent("evt_1", "payment_intent.succeeded", "pi_fake_1", "succeeded");
        doThrow(new IllegalStateException("the database failed")).when(paymentRows).setStatus(anyLong(), eq(PaymentStatus.SUCCEEDED), any());

        deliver(payload).andExpect(status().isInternalServerError()); // Stripe will retry

        assertThat(rows("webhook_event")).isZero();
        assertThat(jdbc.sql("SELECT status FROM payment WHERE booking_id = 7").query(String.class).single()).isEqualTo("PENDING");

        reset(paymentRows); // the database works again
        deliver(payload).andExpect(status().isOk()); // the retry

        assertThat(rows("webhook_event")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM payment WHERE booking_id = 7").query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(rows("outbox")).isEqualTo(1);
    }
}
