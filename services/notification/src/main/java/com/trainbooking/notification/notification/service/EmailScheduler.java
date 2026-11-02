package com.trainbooking.notification.notification.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the sender every second (configurable). Switched off in tests, which call the dispatcher by hand. */
@Component
@ConditionalOnProperty(name = "trainbooking.notification.enabled", havingValue = "true", matchIfMissing = true)
class EmailScheduler {

    private final EmailDispatcher dispatcher;

    EmailScheduler(EmailDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${trainbooking.notification.fixed-delay-ms:1000}")
    void run() {
        dispatcher.dispatchDue();
    }
}
