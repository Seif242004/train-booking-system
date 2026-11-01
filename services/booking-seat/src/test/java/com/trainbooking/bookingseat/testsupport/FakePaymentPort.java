package com.trainbooking.bookingseat.testsupport;

import com.trainbooking.bookingseat.payment.PaymentPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Stands in for the Payment service in the database tests. A test decides what the "cancel" and the "invalidate" answer (or that they
 * throw), and can look at the database at the very moment of the call, to prove that nothing was released before the payment was
 * cancelled.
 */
public class FakePaymentPort implements PaymentPort {

    private volatile LongFunction<CancelOutcome> cancelBehaviour = bookingId -> CancelOutcome.NONE;
    private volatile LongFunction<CancelOutcome> invalidateBehaviour = bookingId -> CancelOutcome.NONE;
    private final List<Long> cancelCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> invalidateCalls = Collections.synchronizedList(new ArrayList<>());

    public void answers(LongFunction<CancelOutcome> behaviour) {
        this.cancelBehaviour = behaviour;
    }

    public void alwaysAnswers(CancelOutcome outcome) {
        this.cancelBehaviour = bookingId -> outcome;
    }

    public void invalidateAnswers(LongFunction<CancelOutcome> behaviour) {
        this.invalidateBehaviour = behaviour;
    }

    public List<Long> cancelCalls() {
        return List.copyOf(cancelCalls);
    }

    public List<Long> invalidateCalls() {
        return List.copyOf(invalidateCalls);
    }

    public void reset() {
        cancelCalls.clear();
        invalidateCalls.clear();
        alwaysAnswers(CancelOutcome.NONE);
        invalidateBehaviour = bookingId -> CancelOutcome.NONE;
    }

    @Override
    public CancelOutcome cancelPending(long bookingId) {
        cancelCalls.add(bookingId);
        return cancelBehaviour.apply(bookingId);
    }

    @Override
    public CancelOutcome invalidate(long bookingId) {
        invalidateCalls.add(bookingId);
        return invalidateBehaviour.apply(bookingId);
    }
}
