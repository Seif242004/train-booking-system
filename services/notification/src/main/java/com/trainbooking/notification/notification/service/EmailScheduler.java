package com.trainbooking.notification.notification.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the sender every second (configurable). Switched off in tests, which call the dispatcher by hand. */
// @Component registers the bean. @ConditionalOnProperty creates it only when trainbooking.notification.enabled is true; it is true by
// default (matchIfMissing). Database tests set it to false and call the dispatcher themselves, so timing is deterministic.
@Component
@ConditionalOnProperty(name = "trainbooking.notification.enabled", havingValue = "true", matchIfMissing = true)
class EmailScheduler {

    private final EmailDispatcher dispatcher;

    EmailScheduler(EmailDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    // @Scheduled with fixedDelay: wait this many ms (default 1000, setting trainbooking.notification.fixed-delay-ms) after the previous run
    // ends, so two runs never overlap. Needs @EnableScheduling in NotificationConfig.
    @Scheduled(fixedDelayString = "${trainbooking.notification.fixed-delay-ms:1000}")
    void run() {
        dispatcher.dispatchDue();
    }
}
