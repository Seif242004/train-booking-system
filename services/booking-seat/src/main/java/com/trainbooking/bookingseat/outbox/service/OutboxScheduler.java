package com.trainbooking.bookingseat.outbox.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the outbox every second (configurable). Switched off in tests, which call the dispatcher by hand. */
@Component
@ConditionalOnProperty(name = "trainbooking.outbox.enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

    private final OutboxDispatcher dispatcher;

    OutboxScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${trainbooking.outbox.fixed-delay-ms:1000}")
    void run() {
        dispatcher.dispatchDue();
    }
}
