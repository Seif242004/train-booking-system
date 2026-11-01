package com.trainbooking.payment.webhook.service;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import com.trainbooking.payment.payment.model.Payment;
import com.trainbooking.payment.payment.repository.PaymentRepository;
import com.trainbooking.payment.payment.service.PaymentService;
import com.trainbooking.payment.refund.service.RefundService;
import com.trainbooking.payment.stripe.StripeNotConfiguredException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles the events Stripe sends (SRS 9.3): payment succeeded or failed, refund succeeded or failed.
 *
 * <ol>
 *   <li><b>Signature first.</b> Nothing is read from the body before its signature is verified with the webhook secret; an invalid or
 *       missing signature (or a timestamp older than five minutes) is refused. Without a webhook secret nothing can be verified, so
 *       every event is refused.</li>
 *   <li><b>At most once (FR-P6).</b> The event id is inserted as the primary key of {@code webhook_event} in the <i>same transaction</i>
 *       as the effect. A second delivery fails the insert and is acknowledged without doing anything; an event whose handling
 *       failed rolls the insert back too, so Stripe's retry processes it again.</li>
 * </ol>
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** What the controller has to answer. */
    public enum Result {
        HANDLED, DUPLICATE, IGNORED
    }

    /** The signature is missing or wrong. Answered as 400. */
    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String webhookSecret;

    public WebhookService(PaymentRepository payments, PaymentService paymentService, RefundService refundService, JdbcClient jdbc,
                          TransactionTemplate tx, Clock clock, @Value("${stripe.webhook-secret:}") String webhookSecret) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.webhookSecret = webhookSecret;
    }

    public Result handle(String payload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new StripeNotConfiguredException("STRIPE_WEBHOOK_SECRET");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidSignatureException("The Stripe-Signature header is missing.");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret, Webhook.DEFAULT_TOLERANCE, clock);
        } catch (SignatureVerificationException e) {
            throw new InvalidSignatureException("The signature of the event does not match.");
        } catch (RuntimeException e) {
            throw new InvalidSignatureException("The event could not be read."); // a body that is not an event at all
        }
        if (event.getId() == null || event.getType() == null) {
            throw new InvalidSignatureException("The body is not a Stripe event."); // correctly signed, but not an event
        }
        return tx.execute(status -> process(event));
    }

    private Result process(Event event) {
        // The primary key makes a second delivery of the same event fail here (ORA-00001). Only THIS insert is read as "already handled":
        // a duplicate-key error from the effects below is a real failure and must make the whole event roll back and be retried.
        try {
            jdbc.sql("INSERT INTO webhook_event (stripe_event_id, event_type, processed_at) VALUES (:id, :type, :now)")
                    .param("id", event.getId()).param("type", event.getType())
                    .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
        } catch (DuplicateKeyException e) {
            return Result.DUPLICATE;
        }

        StripeObject object = event.getDataObjectDeserializer().getObject().orElseGet(() -> {
            try {
                return event.getDataObjectDeserializer().deserializeUnsafe(); // the event has another API version than the library
            } catch (Exception e) {
                return null;
            }
        });
        switch (event.getType()) {
            case "payment_intent.succeeded", "payment_intent.processing", "payment_intent.payment_failed", "payment_intent.canceled" -> {
                if (!(object instanceof PaymentIntent intent)) {
                    return Result.IGNORED;
                }
                return paymentEvent(event.getType(), intent);
            }
            case "refund.updated", "refund.failed" -> {
                if (!(object instanceof Refund refund)) {
                    return Result.IGNORED;
                }
                boolean ours = refundService.applyStripeEvent(refund.getId(), refund.getPaymentIntent(), refund.getStatus(), refund.getFailureReason());
                return ours ? Result.HANDLED : Result.IGNORED;
            }
            default -> {
                return Result.IGNORED; // an event type we did not ask for: acknowledged, so Stripe stops sending it
            }
        }
    }

    private Result paymentEvent(String type, PaymentIntent intent) {
        Optional<Payment> found = payments.findByIntentForUpdate(intent.getId());
        if (found.isEmpty()) {
            log.warn("Stripe event {} for an intent we do not know: {}", type, intent.getId());
            return Result.IGNORED;
        }
        Payment payment = found.get();
        switch (type) {
            case "payment_intent.succeeded" -> paymentService.recordSuccess(payment, "stripe");
            case "payment_intent.processing" -> paymentService.recordProcessing(payment);
            case "payment_intent.payment_failed" -> paymentService.recordFailedAttempt(payment,
                    intent.getLastPaymentError() == null ? null : intent.getLastPaymentError().getMessage());
            default -> paymentService.recordCancelled(payment);
        }
        return Result.HANDLED;
    }
}
