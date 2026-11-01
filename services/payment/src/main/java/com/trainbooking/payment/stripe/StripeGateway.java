package com.trainbooking.payment.stripe;

import java.math.BigDecimal;

/**
 * What Payment needs from Stripe, and nothing more. The rest of the service talks to this interface only, so the payment logic can be
 * tested without Stripe (with a fake that answers and fails on command) and the Stripe client stays in one class.
 *
 * <p>No method may be called inside an open database transaction (CLAUDE.md): a slow or failing Stripe must never hold database
 * locks. Every method either answers or throws {@link StripeCallException}: "the answer is not known", so the caller keeps its state
 * and tries again later.
 */
public interface StripeGateway {

    /** The states of a payment intent that the service distinguishes (Stripe's own status strings, kept as they are). */
    String REQUIRES_PAYMENT_METHOD = "requires_payment_method";
    String PROCESSING = "processing";
    String SUCCEEDED = "succeeded";
    String CANCELED = "canceled";

    /** False when there is no Stripe key: nothing that needs Stripe may even be started (no row is written for a payment that cannot be made). */
    default boolean configured() {
        return true;
    }

    /** A payment intent as Stripe reports it. {@code clientSecret} is only needed by the browser's payment form. */
    record Intent(String id, String status, String clientSecret) {
    }

    /** A refund as Stripe reports it: {@code pending}, {@code succeeded}, {@code failed}, {@code canceled} or {@code requires_action}. */
    record RefundResult(String id, String status, String failureReason) {
    }

    /**
     * Creates a payment intent for a booking. The idempotency key makes a repeated call return the same intent, so a retry after a
     * lost answer never creates a second one.
     */
    Intent createIntent(BigDecimal amount, String currency, long bookingId, String idempotencyKey);

    Intent retrieveIntent(String intentId);

    /**
     * Cancels an intent that nobody has paid. If the intent cannot be cancelled any more (it succeeded, or is being processed), no
     * error is raised: the returned intent says which it is, because that is exactly what the caller has to know (SRS 4.2).
     */
    Intent cancelIntent(String intentId);

    /** Refunds the whole payment. The idempotency key makes a repeated call return the same refund, so a refund can never happen twice. */
    RefundResult createRefund(String intentId, BigDecimal amount, String currency, String idempotencyKey);
}
