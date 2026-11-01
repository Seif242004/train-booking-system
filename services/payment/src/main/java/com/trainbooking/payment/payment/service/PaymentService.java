package com.trainbooking.payment.payment.service;

import com.trainbooking.payment.audit.AuditTrail;
import com.trainbooking.payment.outbox.service.OutboxWriter;
import com.trainbooking.payment.payment.model.CancelOutcome;
import com.trainbooking.payment.payment.model.CreatePaymentRequest;
import com.trainbooking.payment.payment.model.Payment;
import com.trainbooking.payment.payment.model.PaymentConflictException;
import com.trainbooking.payment.payment.model.PaymentSession;
import com.trainbooking.payment.payment.model.PaymentStatus;
import com.trainbooking.payment.payment.repository.PaymentRepository;
import com.trainbooking.payment.stripe.StripeGateway;
import com.trainbooking.payment.stripe.StripeNotConfiguredException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The payment of a booking: creating it while the hold is active, and cancelling it when the hold ends or changes.
 *
 * <p><b>How the steps are ordered.</b> Every change of the state first locks the payment row of the booking
 * ({@code SELECT ... FOR UPDATE}). Stripe is never called while a transaction is open (a slow Stripe must not hold database locks),
 * so each operation is: a short transaction that decides and records the intention, then the Stripe call, then a short transaction
 * that records the result and re-checks that nothing changed in between.
 *
 * <p><b>The race that matters.</b> A payment is being created (Stripe is asked for an intent) at the very moment the hold ends. The
 * ending side marks the row {@code CANCELLED} and {@code ended} (a tombstone when there was no row). When the creating side comes
 * back it finds the tombstone, cancels the intent it just made, and answers 409: the client secret never reaches the user, so nobody
 * can pay for seats that are being released. Cancelling is always done <i>before</i> the seats are released (the caller waits for
 * the answer).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** A row that has been CREATING for longer than this belongs to a call that died; the next request takes it over. */
    private static final Duration CREATING_TIMEOUT = Duration.ofSeconds(60);

    private final PaymentRepository payments;
    private final StripeGateway stripe;
    private final OutboxWriter outbox;
    private final AuditTrail audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String publishableKey;

    public PaymentService(PaymentRepository payments, StripeGateway stripe, OutboxWriter outbox, AuditTrail audit, TransactionTemplate tx,
                          Clock clock, @Value("${stripe.publishable-key:}") String publishableKey) {
        this.payments = payments;
        this.stripe = stripe;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.publishableKey = publishableKey;
    }

    // ---- create -----------------------------------------------------------------------------------------------

    /** What the first transaction of a creation decided. */
    private record Claim(Payment payment, boolean needsIntent) {
    }

    /**
     * Gives the payment page what it needs to show Stripe's form. Asking again while the payment is open returns the same intent
     * (a page reload must not make a second one). The amount is the one Booking worked out; the idempotency key of the Stripe call
     * is the booking and the revision, so a retry of the same payment can never create two intents.
     */
    public PaymentSession create(CreatePaymentRequest request) {
        if (!stripe.configured()) {
            throw new StripeNotConfiguredException("STRIPE_SECRET_KEY"); // before any row is written: a payment that cannot be made leaves nothing behind
        }
        if (publishableKey == null || publishableKey.isBlank()) {
            throw new StripeNotConfiguredException("STRIPE_PUBLISHABLE_KEY"); // the form could not be shown, so no intent is made for it
        }
        Claim claim = tx.execute(status -> claimForCreation(request));
        Payment payment = claim.payment();

        String intentId = payment.intentId();
        String clientSecret;
        if (claim.needsIntent()) {
            // If this throws, the row stays CREATING: the next request takes it over after the timeout, and the idempotency key then
            // returns the intent if Stripe did create it after all.
            StripeGateway.Intent intent = stripe.createIntent(payment.amount(), payment.currency(), payment.bookingId(), idempotencyKey(payment));
            Attach attached = tx.execute(status -> attachIntent(payment, intent));
            if (attached == Attach.LOST) {
                // The hold ended, or the booking changed, while Stripe was creating the intent. Nobody has its client secret.
                cancelQuietly(intent.id());
                throw new PaymentConflictException("payment_closed", "The booking can no longer be paid: its hold ended or changed.");
            }
            // ATTACHED, or ALREADY: a slow earlier call of the same creation (taken over after a minute) attached this very intent first.
            intentId = intent.id();
            clientSecret = intent.clientSecret();
        } else {
            StripeGateway.Intent intent = stripe.retrieveIntent(intentId);
            clientSecret = openIntentSecret(payment, intent);
        }
        return new PaymentSession(payment.id(), clientSecret, publishableKey, payment.amount(), payment.currency());
    }

    private Claim claimForCreation(CreatePaymentRequest request) {
        OffsetDateTime now = now();
        long bookingId = request.bookingId();
        Payment existing = payments.findByBookingForUpdate(bookingId).orElse(null);
        if (existing == null) {
            try {
                Payment created = payments.insert(bookingId, PaymentStatus.CREATING, request.amount(), request.currency(), false, now);
                audit.record("booking-service", "payment", created.id(), "CREATE", Map.of("bookingId", bookingId, "amount", request.amount()));
                return new Claim(created, true);
            } catch (DuplicateKeyException e) {
                // Another request created the row between our read and our insert; it holds the lock now. Ask the caller to retry.
                throw new PaymentConflictException("payment_in_progress", "A payment is being created for this booking. Try again in a moment.");
            }
        }
        if (existing.ended()) {
            throw new PaymentConflictException("payment_closed", "The hold of this booking ended, so it can no longer be paid.");
        }
        return switch (existing.status()) {
            case SUCCEEDED -> throw new PaymentConflictException("already_paid", "This booking is already paid.");
            case PROCESSING -> throw new PaymentConflictException("payment_in_progress", "The payment is being processed.");
            case PENDING -> {
                if (existing.amount().compareTo(request.amount()) != 0 || !existing.currency().equals(request.currency())) {
                    // Booking always invalidates before it changes the amount, so this is a bug on the other side: refuse, never pay a wrong amount.
                    throw new PaymentConflictException("payment_outdated", "The open payment is for another amount.");
                }
                yield new Claim(existing, false);
            }
            case CREATING -> {
                if (existing.updatedAt().plus(CREATING_TIMEOUT).isAfter(now)) {
                    throw new PaymentConflictException("payment_in_progress", "A payment is being created for this booking. Try again in a moment.");
                }
                payments.touch(existing.id(), now); // takes over a creation whose call died
                yield new Claim(existing, true);
            }
            case CANCELLED -> {
                // Invalidated earlier (the booking changed): this is a new payment, the next revision, so a new Stripe intent.
                payments.restart(existing.id(), request.amount(), request.currency(), now);
                audit.record("booking-service", "payment", existing.id(), "RESTART", Map.of("bookingId", bookingId, "amount", request.amount()));
                yield new Claim(payments.findByBooking(bookingId).orElseThrow(), true);
            }
        };
    }

    private enum Attach { ATTACHED, ALREADY, LOST }

    /** LOST if the row was cancelled or ended while Stripe was creating the intent; ALREADY if this same intent is attached and open. */
    private Attach attachIntent(Payment claimed, StripeGateway.Intent intent) {
        Payment current = payments.findByBookingForUpdate(claimed.bookingId()).orElseThrow();
        if (intent.id().equals(current.intentId()) && current.status() == PaymentStatus.PENDING && current.revision() == claimed.revision() && !current.ended()) {
            return Attach.ALREADY;
        }
        if (current.status() != PaymentStatus.CREATING || current.revision() != claimed.revision() || current.ended()) {
            return Attach.LOST;
        }
        payments.setIntent(current.id(), intent.id(), PaymentStatus.PENDING, now());
        audit.record("booking-service", "payment", current.id(), "INTENT", Map.of("intent", intent.id()));
        return Attach.ATTACHED;
    }

    /** The client secret of an intent that is still open; any other state of the intent means the page must not pay it. */
    private String openIntentSecret(Payment payment, StripeGateway.Intent intent) {
        switch (intent.status()) {
            case StripeGateway.SUCCEEDED, StripeGateway.PROCESSING ->
                    throw new PaymentConflictException("payment_in_progress", "The payment is being processed.");
            case StripeGateway.CANCELED -> {
                tx.executeWithoutResult(status -> payments.findByBookingForUpdate(payment.bookingId())
                        .filter(p -> p.status() == PaymentStatus.PENDING)
                        .ifPresent(p -> payments.setStatus(p.id(), PaymentStatus.CANCELLED, now())));
                throw new PaymentConflictException("payment_outdated", "The open payment was cancelled. Try again.");
            }
            default -> {
                return intent.clientSecret();
            }
        }
    }

    // ---- cancel / invalidate ------------------------------------------------------------------------------------

    /** What the first transaction of a cancellation decided: an answer already, or an intent that Stripe must be asked to cancel. */
    private record Plan(CancelOutcome outcome, String intentToCancel) {
    }

    /**
     * The hold ended (the five minutes ran out, the user let go of everything, the trip was cancelled): cancel the open payment.
     * After this the payment can never be created again. The caller releases the seats only after a NONE or CANCELLED answer.
     */
    public CancelOutcome cancelPending(long bookingId) {
        return cancel(bookingId, true);
    }

    /**
     * The booking changes (a seat was picked or released after the user had been to the payment page): cancel the open payment so
     * that its amount can never differ from the booking's. A new payment may be created afterwards.
     */
    public CancelOutcome invalidate(long bookingId) {
        return cancel(bookingId, false);
    }

    private CancelOutcome cancel(long bookingId, boolean ending) {
        // At most a few rounds: a round repeats only if another request replaced the payment while Stripe was being asked.
        for (int round = 1; round <= 3; round++) {
            Plan plan = tx.execute(status -> planCancel(bookingId, ending));
            if (plan.intentToCancel() == null) {
                return plan.outcome();
            }
            // Stripe first, outside any transaction. If it cannot be reached this throws and the row stays as it was (PENDING, and
            // ended when the hold is ending): the caller keeps the seats held and asks again.
            StripeGateway.Intent after = stripe.cancelIntent(plan.intentToCancel());
            CancelOutcome outcome = tx.execute(status -> settleCancel(bookingId, plan.intentToCancel(), after.status()));
            if (outcome != null) {
                return outcome;
            }
        }
        throw new PaymentConflictException("payment_in_progress", "The payment keeps changing. Try again in a moment.");
    }

    private Plan planCancel(long bookingId, boolean ending) {
        Payment payment = payments.findByBookingForUpdate(bookingId).orElse(null);
        if (payment == null) {
            if (ending) {
                // A creation may already be on its way (the caller set payment_started before asking). The tombstone refuses it.
                Payment tombstone = payments.insert(bookingId, PaymentStatus.CANCELLED, java.math.BigDecimal.ZERO, "EGP", true, now());
                audit.record("booking-service", "payment", tombstone.id(), "TOMBSTONE", Map.of("bookingId", bookingId));
            }
            return new Plan(CancelOutcome.NONE, null);
        }
        if (ending && !payment.ended()) {
            payments.markEnded(payment.id());
        }
        switch (payment.status()) {
            case SUCCEEDED:
                return new Plan(CancelOutcome.SUCCEEDED, null);
            case CANCELLED:
                return new Plan(CancelOutcome.CANCELLED, null);
            case CREATING:
                // The creator will find CANCELLED when it comes back, cancel the intent it made, and never hand out the secret.
                payments.setStatus(payment.id(), PaymentStatus.CANCELLED, now());
                audit.record("booking-service", "payment", payment.id(), ending ? "CANCEL" : "INVALIDATE", Map.of("bookingId", bookingId, "was", "CREATING"));
                return new Plan(CancelOutcome.CANCELLED, null);
            case PENDING:
            case PROCESSING: // ask Stripe again: it may have settled, and the webhook may be late. A processing intent is not cancelled, it just reports its state.
            default:
                return new Plan(CancelOutcome.CANCELLED, payment.intentId());
        }
    }

    private CancelOutcome settleCancel(long bookingId, String intentId, String stripeStatus) {
        Payment payment = payments.findByBookingForUpdate(bookingId).orElseThrow();
        if (!intentId.equals(payment.intentId())) {
            // Another request replaced the payment (a new intent) while we were asking Stripe about the old one. What we learned is about
            // an intent that is no longer this booking's payment: do not touch the row, and plan again against the current one.
            return null;
        }
        switch (stripeStatus) {
            case StripeGateway.CANCELED -> {
                if (payment.status() == PaymentStatus.PENDING || payment.status() == PaymentStatus.PROCESSING) {
                    payments.setStatus(payment.id(), PaymentStatus.CANCELLED, now());
                    audit.record("booking-service", "payment", payment.id(), "CANCEL", Map.of("bookingId", bookingId, "intent", intentId));
                }
                return CancelOutcome.CANCELLED;
            }
            case StripeGateway.SUCCEEDED -> {
                // The payment won the race with the cancellation: the money is taken, the booking is confirmed (SRS 4.2).
                recordSuccess(payment, "booking-service");
                return CancelOutcome.SUCCEEDED;
            }
            case StripeGateway.PROCESSING -> {
                if (payment.status() == PaymentStatus.PENDING) {
                    payments.setStatus(payment.id(), PaymentStatus.PROCESSING, now());
                }
                return CancelOutcome.PROCESSING;
            }
            default -> {
                // The intent is open again (for example a payment attempt just failed): not cancelled, so the caller must wait and ask again.
                log.warn("Stripe says intent {} is {} after a cancel for booking {}", intentId, stripeStatus, bookingId);
                return CancelOutcome.PROCESSING;
            }
        }
    }

    // ---- results Stripe tells us (webhook) and the success shared by both paths ---------------------------------------

    /**
     * Records that the money is taken and queues the news for Booking, in the transaction of the caller. Safe to call again for a
     * payment that is already SUCCEEDED (nothing is queued twice).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(Payment payment, String actor) {
        if (payment.status() == PaymentStatus.SUCCEEDED) {
            return;
        }
        payments.setStatus(payment.id(), PaymentStatus.SUCCEEDED, now());
        outbox.paymentSucceeded(payment.bookingId(), payment.id(), payment.amount(), payment.currency());
        audit.record(actor, "payment", payment.id(), "SUCCEEDED", Map.of("bookingId", payment.bookingId(), "amount", payment.amount()));
    }

    /** The payment is being processed by Stripe. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordProcessing(Payment payment) {
        if (payment.status() == PaymentStatus.PENDING) {
            payments.setStatus(payment.id(), PaymentStatus.PROCESSING, now());
        }
    }

    /**
     * An attempt to pay failed (declined card...). The intent stays open and so does the hold, so the user can try again within the
     * remaining time (FR-P5); only the reason is kept.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFailedAttempt(Payment payment, String reason) {
        if (payment.status() == PaymentStatus.PROCESSING) {
            payments.setStatus(payment.id(), PaymentStatus.PENDING, now());
        }
        payments.setLastError(payment.id(), reason == null ? null : reason.substring(0, Math.min(reason.length(), 480)), now());
    }

    /** Stripe says the intent was cancelled (by us, or by its own expiry). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCancelled(Payment payment) {
        if (payment.status() == PaymentStatus.PENDING || payment.status() == PaymentStatus.PROCESSING) {
            payments.setStatus(payment.id(), PaymentStatus.CANCELLED, now());
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private void cancelQuietly(String intentId) {
        try {
            stripe.cancelIntent(intentId);
        } catch (RuntimeException e) {
            // Not fatal: nobody has the client secret, so the intent cannot be paid. Stripe expires unpaid intents by itself.
            log.warn("Could not cancel the unused intent {}: {}", intentId, e.getMessage());
        }
    }

    private static String idempotencyKey(Payment payment) {
        return "payment-" + payment.bookingId() + "-r" + payment.revision();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
