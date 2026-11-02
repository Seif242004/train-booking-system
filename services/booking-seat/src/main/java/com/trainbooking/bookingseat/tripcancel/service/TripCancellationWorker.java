package com.trainbooking.bookingseat.tripcancel.service;

import com.trainbooking.bookingseat.tripcancel.repository.TripCancellationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps trying (every few seconds, configurable) for every trip that is CANCELLING: Train &amp; Schedule that was down, a payment that was
 * still processing, a booking that failed. "Each booking is processed independently and retried" (Flow 8, step 7). Switched off in tests.
 */
@Component
@ConditionalOnProperty(name = "trainbooking.tripcancel.enabled", havingValue = "true", matchIfMissing = true)
class TripCancellationWorker {

    private static final Logger log = LoggerFactory.getLogger(TripCancellationWorker.class);

    private final TripCancellationRepository trips;
    private final TripCancellationService service;

    TripCancellationWorker(TripCancellationRepository trips, TripCancellationService service) {
        this.trips = trips;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${trainbooking.tripcancel.fixed-delay-ms:5000}")
    void run() {
        for (long tripId : trips.findCancellingTripIds()) {
            try {
                service.process(tripId);
            } catch (RuntimeException e) {
                log.error("A pass over cancelled trip {} failed: {}", tripId, e.getMessage()); // the next pass tries again
            }
        }
    }
}
