package com.trainbooking.trainschedule.trip.service;

import com.trainbooking.trainschedule.common.SnapshotChanges;
import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.repository.TripRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pushes the snapshots of the affected trips again when something inside them was edited (see {@link SnapshotChanges}).
 *
 * <p>The edit calls it while its transaction is open. The work is not done at once but registered to run <b>just before
 * the commit</b>, so that a trip is pushed once even if an edit touches several things (a coach and its seats), and so that
 * it sees the edit that was just made (it runs on the same connection). A refusal of Booking and Seat then makes the commit
 * fail and the edit is rolled back, exactly like creating or changing a trip.
 */
// @Component: a plain Spring bean, package-private; it is THE implementation of SnapshotChanges that the edit services receive.
// Transaction callbacks used below (Spring TransactionSynchronization): beforeCommit runs after the method body but before the database
// commit, so an exception from it aborts the commit and rolls the edit back; afterCompletion always runs at the end (commit or rollback).
@Component
class TripSnapshotRefresher implements SnapshotChanges {

    /** What changed: a train (with its coaches and seats), a route (name or fares) or a station. */
    private enum Scope { TRAIN, ROUTE, STATION }

    /** One pending change. A record's equals/hashCode make the Set drop repeats of the same change. */
    private record Change(Scope scope, long id, String adminId) {
    }

    /** Key under which the pending set is stored on the current transaction, so all edits of one transaction share one set. */
    private static final Object RESOURCE_KEY = TripSnapshotRefresher.class;

    private final TripRepository trips;
    // The publisher needs the coach, seat and fare services, which call this class: it is looked up when it is needed.
    /** ObjectProvider = a lazy handle to a bean, resolved at use time. */
    private final ObjectProvider<TripSnapshotPublisher> publisher;
    /** Source of "now" (UTC); tests replace it. A trip is "future" if its first departure is after now. */
    private final Clock clock;

    TripSnapshotRefresher(TripRepository trips, ObjectProvider<TripSnapshotPublisher> publisher, Clock clock) {
        this.trips = trips;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    /** Remembers that a train (or one of its coaches, seats or standing capacities) changed. Nothing is sent yet. */
    public void trainChanged(long trainId, String adminId) {
        register(new Change(Scope.TRAIN, trainId, adminId));
    }

    @Override
    public void routeChanged(long routeId, String adminId) {
        register(new Change(Scope.ROUTE, routeId, adminId));
    }

    @Override
    public void stationChanged(long stationId, String adminId) {
        register(new Change(Scope.STATION, stationId, adminId));
    }

    // Why a set per transaction: editing a coach and its seats in one request must push each trip only once, and as late as possible.
    // Without an open transaction there is nothing to wait for, so it pushes immediately.
    private void register(Change change) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            push(Set.of(change)); // not inside a transaction: nothing to wait for
            return;
        }
        // @SuppressWarnings("unchecked"): the compiler cannot check the generic type of the stored resource; we know it is always a
        // Set<Change>.
        @SuppressWarnings("unchecked")
        Set<Change> pending = (Set<Change>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (pending == null) {
            Set<Change> created = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(RESOURCE_KEY, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    push(created);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY);
                }
            });
            pending = created;
        }
        pending.add(change);
    }

    /** Pushes every future active trip that the changes touch, each trip once, in the order of the changes. */
    private void push(Set<Change> changes) {
        Set<Long> done = new LinkedHashSet<>();
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Change change : changes) {
            List<Long> ids = switch (change.scope()) {
                case TRAIN -> trips.findFutureActiveIdsByTrain(change.id(), now);
                case ROUTE -> trips.findFutureActiveIdsByRoute(change.id(), now);
                case STATION -> trips.findFutureActiveIdsByStation(change.id(), now);
            };
            for (long tripId : ids) {
                if (done.add(tripId)) {
                    Trip trip = trips.findById(tripId).orElseThrow();
                    publisher.getObject().publish(trip, change.adminId());
                }
            }
        }
    }
}
