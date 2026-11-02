package com.trainbooking.payment.testsupport;

import com.trainbooking.payment.stripe.StripeCallException;
import com.trainbooking.payment.stripe.StripeGateway;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Stands in for Stripe. It behaves like the real one where it matters to the logic: an idempotency key returns the same intent or
 * refund again, an intent that was paid cannot be cancelled (the cancel just reports its state), and it can be switched off to test
 * what happens when Stripe cannot be reached. A test can also run code at the very moment of a call, to put another operation in
 * the middle of one (the race between creating a payment and the hold ending).
 */
public class FakeStripeGateway implements StripeGateway {

    public record IntentCall(BigDecimal amount, String currency, long bookingId, String idempotencyKey) {
    }

    public record RefundCall(String intentId, BigDecimal amount, String currency, String idempotencyKey) {
    }

    private final AtomicInteger counter = new AtomicInteger();
    private final Map<String, Intent> intents = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, String> intentByKey = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, RefundResult> refundByKey = Collections.synchronizedMap(new HashMap<>());
    private final List<IntentCall> createCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> cancelCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<RefundCall> refundCalls = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean down;
    private volatile boolean configured = true;
    private volatile boolean rejectRefunds;
    private volatile RuntimeException createFailure;
    private volatile Runnable whileSendingRefund = () -> {
    };
    private volatile String refundStatus = "succeeded";
    private volatile Consumer<IntentCall> afterIntentCreated = call -> {
    };
    private volatile Consumer<String> beforeCancel = intentId -> {
    };

    public void reset() {
        counter.set(0);
        intents.clear();
        intentByKey.clear();
        refundByKey.clear();
        createCalls.clear();
        cancelCalls.clear();
        refundCalls.clear();
        down = false;
        configured = true;
        rejectRefunds = false;
        createFailure = null;
        whileSendingRefund = () -> {
        };
        refundStatus = "succeeded";
        afterIntentCreated = call -> {
        };
        beforeCancel = intentId -> {
        };
    }

    /** Runs while a refund is being sent to Stripe (the call is in flight). */
    public void whileSendingRefund(Runnable action) {
        this.whileSendingRefund = action;
    }

    /** The next creations of an intent fail with this. */
    public void createFails(RuntimeException failure) {
        this.createFailure = failure;
    }

    /** Stripe refuses every refund for good (a 4xx such as "already refunded"). */
    public void rejectRefunds(boolean reject) {
        this.rejectRefunds = reject;
    }

    /** There is no Stripe key in the settings. */
    public void configured(boolean configured) {
        this.configured = configured;
    }

    @Override
    public boolean configured() {
        return configured;
    }

    /** Stripe cannot be reached: every call throws. */
    public void down(boolean down) {
        this.down = down;
    }

    /** What a new refund reports: succeeded (default), pending, failed. */
    public void refundStatus(String status) {
        this.refundStatus = status;
    }

    /** Runs right after Stripe created an intent and before the answer goes back (the creation is "in flight"). */
    public void afterIntentCreated(Consumer<IntentCall> action) {
        this.afterIntentCreated = action;
    }

    /** Runs when a cancel arrives, before it is applied. */
    public void beforeCancel(Consumer<String> action) {
        this.beforeCancel = action;
    }

    /** The customer pays (or the payment changes state) at Stripe. */
    public void setStatus(String intentId, String status) {
        intents.computeIfPresent(intentId, (id, old) -> new Intent(id, status, old.clientSecret()));
    }

    public String statusOf(String intentId) {
        return intents.get(intentId).status();
    }

    public List<IntentCall> createCalls() {
        return List.copyOf(createCalls);
    }

    public List<String> cancelCalls() {
        return List.copyOf(cancelCalls);
    }

    public List<RefundCall> refundCalls() {
        return List.copyOf(refundCalls);
    }

    public int intentCount() {
        return intents.size();
    }

    @Override
    public Intent createIntent(BigDecimal amount, String currency, long bookingId, String idempotencyKey) {
        checkUp();
        IntentCall call = new IntentCall(amount, currency, bookingId, idempotencyKey);
        createCalls.add(call);
        if (createFailure != null) {
            throw createFailure;
        }
        String existing = intentByKey.get(idempotencyKey);
        if (existing != null) {
            return intents.get(existing);
        }
        String id = "pi_fake_" + counter.incrementAndGet();
        Intent intent = new Intent(id, REQUIRES_PAYMENT_METHOD, id + "_secret");
        intents.put(id, intent);
        intentByKey.put(idempotencyKey, id);
        afterIntentCreated.accept(call);
        return intent;
    }

    @Override
    public Intent retrieveIntent(String intentId) {
        checkUp();
        return intents.get(intentId);
    }

    @Override
    public Intent cancelIntent(String intentId) {
        checkUp();
        cancelCalls.add(intentId);
        beforeCancel.accept(intentId);
        Intent intent = intents.get(intentId);
        if (REQUIRES_PAYMENT_METHOD.equals(intent.status())) {
            intent = new Intent(intentId, CANCELED, intent.clientSecret());
            intents.put(intentId, intent);
        }
        return intent; // succeeded or processing: too late to cancel, the state says so
    }

    @Override
    public RefundResult createRefund(String intentId, BigDecimal amount, String currency, String idempotencyKey) {
        checkUp();
        refundCalls.add(new RefundCall(intentId, amount, currency, idempotencyKey));
        whileSendingRefund.run();
        if (rejectRefunds) {
            throw new com.trainbooking.payment.stripe.StripeRejectedException("charge_already_refunded (test)", null);
        }
        return refundByKey.computeIfAbsent(idempotencyKey,
                key -> new RefundResult("re_fake_" + counter.incrementAndGet(), refundStatus, "failed".equals(refundStatus) ? "declined" : null));
    }

    private void checkUp() {
        if (down) {
            throw new StripeCallException("Stripe is down (test)");
        }
    }
}
