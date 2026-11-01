package com.trainbooking.payment.outbox.service;

import com.trainbooking.payment.refund.service.RefundRetrier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the two retry loops of this service every second (configurable): the delivery of the outbox to Booking and Seat, and the
 * refunds Stripe could not be reached for. Switched off in tests, which call the loops by hand.
 */
@Component
@ConditionalOnProperty(name = "trainbooking.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private final OutboxDispatcher dispatcher;
    private final RefundRetrier retrier;

    public OutboxScheduler(OutboxDispatcher dispatcher, RefundRetrier retrier) {
        this.dispatcher = dispatcher;
        this.retrier = retrier;
    }

    @Scheduled(fixedDelayString = "${trainbooking.outbox.fixed-delay-ms:1000}")
    void deliver() {
        dispatcher.dispatchDue();
    }

    @Scheduled(fixedDelayString = "${trainbooking.outbox.fixed-delay-ms:1000}")
    void retryRefunds() {
        retrier.retryDue();
    }
}
