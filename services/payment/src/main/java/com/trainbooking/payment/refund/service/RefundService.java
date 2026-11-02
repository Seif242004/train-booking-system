package com.trainbooking.payment.refund.service;

import com.trainbooking.payment.audit.AuditTrail;
import com.trainbooking.payment.outbox.service.OutboxWriter;
import com.trainbooking.payment.payment.model.Payment;
import com.trainbooking.payment.payment.model.PaymentStatus;
import com.trainbooking.payment.payment.repository.PaymentRepository;
import com.trainbooking.payment.refund.model.NoPaymentToRefundException;
import com.trainbooking.payment.refund.model.Refund;
import com.trainbooking.payment.refund.model.RefundStatus;
import com.trainbooking.payment.refund.repository.RefundRepository;
import com.trainbooking.payment.stripe.StripeCallException;
import com.trainbooking.payment.stripe.StripeGateway;
import com.trainbooking.payment.stripe.StripeRejectedException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The full refund of a booking's payment (SRS 7.2): requested by Booking and Seat when a booking is cancelled, or when money arrives
 * for a hold that no longer exists. A timeout never gets here: a timed-out hold is cancelled before any money moves (FR-P4).
 *
 * <p>The refund row is created first, in one transaction with the news for Booking (REFUND_PENDING). Only then is Stripe asked, with
 * the row id as idempotency key, so a retry after a lost answer can never refund twice, and the unique key on the booking allows
 * one refund per booking. If Stripe cannot be reached the row stays PENDING and {@link RefundRetrier} asks again with a backoff.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    /** An outage is logged as an error every hour (the retries are a minute apart) but never ends the refund. */
    static final int LOG_EVERY_ATTEMPTS = 60;

    /** How long the retrier leaves a new refund alone, while the request that created it is talking to Stripe. */
    static final java.time.Duration FIRST_ATTEMPT_GRACE = java.time.Duration.ofSeconds(30);

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final StripeGateway stripe;
    private final OutboxWriter outbox;
    private final AuditTrail audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundService(RefundRepository refunds, PaymentRepository payments, StripeGateway stripe, OutboxWriter outbox, AuditTrail audit,
                         TransactionTemplate tx, Clock clock) {
        this.refunds = refunds;
        this.payments = payments;
        this.stripe = stripe;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /** @throws NoPaymentToRefundException if the booking has no payment that succeeded */
    public Refund request(long bookingId, String reason) {
        record Created(Refund refund, boolean isNew) {
        }
        Created created = tx.execute(status -> {
            Payment payment = payments.findByBookingForUpdate(bookingId).filter(p -> p.status() == PaymentStatus.SUCCEEDED)
                    .orElseThrow(() -> new NoPaymentToRefundException(bookingId));
            Optional<Refund> existing = refunds.findByBooking(bookingId);
            if (existing.isPresent()) {
                return new Created(existing.get(), false); // a repeated request: the same refund, nothing new
            }
            // The request that creates the refund sends it to Stripe itself, right after this transaction. The retrier must not pick it up
            // meanwhile (two calls with the same idempotency key at once make Stripe answer "key in use"), so it is not due for a while;
            // if the first attempt fails, scheduleRetry sets the real time of the next one.
            Refund refund = refunds.insert(bookingId, payment.id(), payment.amount(), reason, now(), now().plus(FIRST_ATTEMPT_GRACE));
            outbox.refundChanged(bookingId, "REFUND_PENDING");
            audit.record("booking-service", "refund", refund.id(), "REQUEST", Map.of("bookingId", bookingId, "amount", refund.amount(), "reason", String.valueOf(reason)));
            return new Created(refund, true);
        });
        if (created.isNew()) {
            attempt(created.refund());
        }
        return refunds.findByBooking(bookingId).orElseThrow();
    }

    /** Asks Stripe for the refund (outside any transaction) and records the answer. Never throws for a Stripe outage: it schedules a retry. */
    public void attempt(Refund refund) {
        Payment payment = payments.findByBooking(refund.bookingId()).orElseThrow();
        StripeGateway.RefundResult result;
        try {
            result = stripe.createRefund(payment.intentId(), refund.amount(), payment.currency(), "refund-" + refund.id());
        } catch (StripeRejectedException e) {
            // Stripe said no for good (already refunded, amount too large, expired charge...): retrying cannot help. Flag it (REFUND_FAILED)
            // so an admin sees it, instead of leaving it "pending" forever.
            log.error("Stripe refused refund {} of booking {}: {}", refund.id(), refund.bookingId(), e.getMessage());
            tx.executeWithoutResult(status -> applyStripeResult(refund.id(), null, "failed", e.getMessage()));
            return;
        } catch (RuntimeException e) {
            if ((refund.attempts() + 1) % LOG_EVERY_ATTEMPTS == 0) {
                log.error("Refund {} of booking {} could not be sent to Stripe after {} attempts, still trying", refund.id(), refund.bookingId(), refund.attempts() + 1);
            }
            // Stripe is down, or not configured: the refund stays PENDING and is tried again later, every minute at most (never lost, never
            // doubled, never given up: the passenger's money is owed). Only a refusal of Stripe for good makes it FAILED.
            OffsetDateTime next = now().plusSeconds(Math.min(60, 1L << Math.min(refund.attempts() + 1, 6)));
            tx.executeWithoutResult(status -> refunds.scheduleRetry(refund.id(), next, e.getMessage(), now()));
            if (!(e instanceof StripeCallException)) {
                log.warn("Refund {} could not be sent to Stripe: {}", refund.id(), e.getMessage());
            }
            return;
        }
        tx.executeWithoutResult(status -> applyStripeResult(refund.id(), result.id(), result.status(), result.failureReason()));
    }

    /**
     * Applies what Stripe says about the refund (from the answer of the create call or from a webhook), in the caller's transaction.
     * Once the refund is final (SUCCEEDED or FAILED) later reports change nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyStripeResult(long refundId, String stripeRefundId, String stripeStatus, String failureReason) {
        Refund refund = refunds.findByIdForUpdate(refundId).orElseThrow();
        apply(refund, stripeRefundId, stripeStatus, failureReason);
    }

    /**
     * A refund webhook. The refund is found by its Stripe id; if the webhook is faster than the answer of our own create call, it is
     * found through the payment intent instead and adopts the Stripe id.
     *
     * @return false if this service has no such refund (not one of ours)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean applyStripeEvent(String stripeRefundId, String paymentIntentId, String stripeStatus, String failureReason) {
        Refund refund = refunds.findByStripeIdForUpdate(stripeRefundId).orElse(null);
        if (refund == null && paymentIntentId != null) {
            refund = payments.findByIntentForUpdate(paymentIntentId).flatMap(p -> refunds.findByBooking(p.bookingId()))
                    .filter(r -> r.stripeRefundId() == null).flatMap(r -> refunds.findByIdForUpdate(r.id())).orElse(null);
        }
        if (refund == null) {
            return false;
        }
        apply(refund, stripeRefundId, stripeStatus, failureReason);
        return true;
    }

    private void apply(Refund refund, String stripeRefundId, String stripeStatus, String failureReason) {
        if (refund.status() != RefundStatus.PENDING) {
            return; // final already
        }
        RefundStatus target = switch (stripeStatus) {
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "failed", "canceled" -> RefundStatus.FAILED;
            default -> RefundStatus.PENDING; // pending, requires_action: Stripe will tell us with a webhook
        };
        refunds.setStripeRefund(refund.id(), stripeRefundId, target, failureReason, now());
        if (target != RefundStatus.PENDING) {
            outbox.refundChanged(refund.bookingId(), target == RefundStatus.SUCCEEDED ? "REFUNDED" : "REFUND_FAILED");
            audit.record("stripe", "refund", refund.id(), target.name(), Map.of("bookingId", refund.bookingId(), "stripeRefund", String.valueOf(stripeRefundId)));
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
