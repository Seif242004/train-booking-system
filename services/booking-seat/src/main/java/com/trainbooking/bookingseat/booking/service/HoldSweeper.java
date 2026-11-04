package com.trainbooking.bookingseat.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The sweeper (SRS 4.2): every second it looks for holds that ran out and hands them to {@link ExpiryService}. It uses
 * {@code fixedDelay}, so a slow sweep never overlaps the next one; the delay is configurable. A hold is released within seconds
 * of its expiry (NFR-3); until then it still counts as held.
 */
// @Configuration + @EnableScheduling: switch on Spring's scheduler. @ConditionalOnProperty: the whole class is a bean only if
// trainbooking.sweeper.enabled is true (the default, matchIfMissing), so tests can turn the background sweep off and call the service by
// hand.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "trainbooking.sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);

    private final ExpiryService expiry;

    public HoldSweeper(ExpiryService expiry) {
        this.expiry = expiry;
    }

    // @Scheduled(fixedDelay): run, finish, wait 1000 ms (property trainbooking.sweeper.fixed-delay-ms), run again. Unlike fixedRate it
    // never
    // starts a run while the previous one is still going.
    @Scheduled(fixedDelayString = "${trainbooking.sweeper.fixed-delay-ms:1000}")
    public void sweep() {
        try {
            // ExpiryService does the work; any exception is caught here so the schedule continues.
            expiry.sweep();
        } catch (RuntimeException e) {
            log.error("The sweep failed; it runs again at the next tick", e); // an exception must never end the schedule
        }
    }
}
