package com.trainbooking.bookingseat.testsupport;

import com.trainbooking.bookingseat.payment.PaymentPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Stands in for the Payment service in the database tests. A test decides what the "cancel" answers (or that it throws), and
 * can look at the database at the very moment of the call, to prove that nothing was released before the payment was cancelled.
 */
public class FakePaymentPort implements PaymentPort {

    private volatile LongFunction<CancelOutcome> behaviour = bookingId -> CancelOutcome.NONE;
    private final List<Long> cancelCalls = Collections.synchronizedList(new ArrayList<>());

    public void answers(LongFunction<CancelOutcome> behaviour) {
        this.behaviour = behaviour;
    }

    public void alwaysAnswers(CancelOutcome outcome) {
        this.behaviour = bookingId -> outcome;
    }

    public List<Long> cancelCalls() {
        return List.copyOf(cancelCalls);
    }

    public void reset() {
        cancelCalls.clear();
        alwaysAnswers(CancelOutcome.NONE);
    }

    @Override
    public CancelOutcome cancelPending(long bookingId) {
        cancelCalls.add(bookingId);
        return behaviour.apply(bookingId);
    }
}
