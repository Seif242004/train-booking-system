package com.trainbooking.trainschedule.trip.service;

import com.trainbooking.trainschedule.trip.model.Trip;
import org.springframework.stereotype.Component;

/**
 * The place where a trip is handed over to Booking & Seat.
 *
 * <p><b>TODO(slice 5, not implemented yet):</b> when a trip is created or changed, push an immutable snapshot of it to
 * Booking & Seat over REST: the coaches, seats and standing capacity, the stops and times, and the fares (CLAUDE.md,
 * "Data copy"). Booking upserts it with MERGE. The push goes through a DB outbox table and a {@code @Scheduled} retry
 * poller in this service, and Train & Schedule commits the trip only after Booking accepts it. Booking & Seat does not
 * exist yet, so for now this does nothing: trips are saved here only.
 */
@Component
class TripSnapshotPublisher {

    void publish(Trip trip) {
        // Intentionally empty until slice 5. See the TODO above.
    }
}
