package com.trainbooking.bookingseat.snapshot.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.snapshot.model.SnapshotConflictException;
import com.trainbooking.bookingseat.snapshot.model.SnapshotResult;
import com.trainbooking.bookingseat.snapshot.model.TripSnapshot;
import com.trainbooking.bookingseat.snapshot.repository.SnapshotRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the snapshot that Train and Schedule pushes (CLAUDE.md, "Data copy"). The whole snapshot is applied in one
 * transaction: if anything fails, nothing of it is stored, and Train and Schedule does not commit the trip either.
 */
@Service
public class SnapshotService {

    private static final String ENTITY = "trip_snapshot";
    private static final String OPEN = "OPEN";

    private final SnapshotRepository repository;
    private final AuditTrail audit;

    public SnapshotService(SnapshotRepository repository, AuditTrail audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /**
     * Creates or updates the copy of a trip. The caller has already checked that the snapshot is consistent in itself
     * ({@link SnapshotValidator}); what is checked here is whether it fits what Booking already holds for the trip:
     * <ul>
     *   <li>a trip that is CANCELLING or CANCELLED accepts no snapshot;</li>
     *   <li>once a trip has holds or bookings, its date, train, stops with their times, and fares are frozen (a passenger
     *       has been shown, and may have paid, that price for that journey);</li>
     *   <li>capacity must not drop below what is in use: a seat with holds or bookings cannot be removed or moved to
     *       another coach, and the standing capacity of a coach cannot go below the places used (FR-A9).</li>
     * </ul>
     * The row of the trip is locked first, so two pushes for the same trip are applied one after the other.
     */
    @Transactional
    public SnapshotResult apply(long tripId, TripSnapshot snapshot, String actor) {
        Optional<String> status = repository.lockTrip(tripId);
        boolean created = status.isEmpty();
        SnapshotRepository.Counts before = created ? null : repository.counts(tripId);

        if (!created) {
            if (!OPEN.equals(status.get())) {
                throw new SnapshotConflictException("trip_not_open", "Trip " + tripId + " is " + status.get() + " and accepts no changes.", List.of());
            }
            if (repository.hasReservations(tripId)) {
                requireFrozenFactsUnchanged(tripId, snapshot);
                requireCapacityStillCoversTheReservations(tripId, snapshot);
            }
        }

        int segments = snapshot.stops().size() - 1;
        // The order matters for the foreign keys: parents before children when adding, children before parents when removing.
        repository.mergeTrip(tripId, snapshot);
        repository.replaceStops(tripId, snapshot.stops());
        repository.mergeCoaches(tripId, snapshot.coaches());
        try {
            repository.replaceSeats(tripId, snapshot.seats());
            repository.deleteCoachesNotIn(tripId, snapshot.coaches());
            repository.replaceStanding(tripId, snapshot.coaches(), segments);
        } catch (DataIntegrityViolationException e) {
            // The checks above make this unreachable in normal use. It is the database refusing what the checks did not
            // foresee, for example a hold that committed after the check: the foreign key of seat_reservation (ORA-02292)
            // or CHECK used <= capacity (ORA-02290). Either way the change would conflict with reservations.
            throw new SnapshotConflictException("capacity_conflict", "The change conflicts with holds or bookings of the trip.", List.of());
        }
        repository.replaceFares(tripId, snapshot.fares());

        SnapshotRepository.Counts after = repository.counts(tripId);
        audit.change(actor, ENTITY, tripId, created ? "CREATE" : "UPDATE", before, after);
        return new SnapshotResult(tripId, created, after.stops(), after.coaches(), after.seats(), after.fares(), after.standingCounters());
    }

    /**
     * Removes the copy of a trip (the trip was deleted in Train and Schedule). Refused if the trip has holds or bookings: such
     * a trip is cancelled (Flow 8), not deleted. A trip that Booking never had is not an error.
     */
    @Transactional
    public void delete(long tripId, String actor) {
        if (repository.lockTrip(tripId).isEmpty()) {
            return;
        }
        if (repository.hasReservations(tripId)) {
            throw new SnapshotConflictException("trip_has_reservations", "Trip " + tripId + " has holds or bookings, so it cannot be deleted.", List.of());
        }
        SnapshotRepository.Counts before = repository.counts(tripId);
        repository.deleteTrip(tripId);
        audit.change(actor, ENTITY, tripId, "DELETE", before, null);
    }

    // ---- the checks ---------------------------------------------------------------------------------------------------

    private void requireFrozenFactsUnchanged(long tripId, TripSnapshot snapshot) {
        SnapshotRepository.FrozenFacts stored = repository.readFrozenFacts(tripId);
        List<ApiError.Detail> changed = new ArrayList<>();
        if (!stored.departureDate().equals(snapshot.departureDate())) {
            changed.add(new ApiError.Detail("departureDate", "cannot change: the trip has holds or bookings"));
        }
        if (!stored.trainNumber().equals(snapshot.train().number())) {
            changed.add(new ApiError.Detail("train.number", "cannot change: the trip has holds or bookings"));
        }
        if (!sameStops(stored, snapshot)) {
            changed.add(new ApiError.Detail("stops", "the stations and times cannot change: the trip has holds or bookings"));
        }
        if (!sameFares(stored, snapshot)) {
            changed.add(new ApiError.Detail("fares", "cannot change: the trip has holds or bookings"));
        }
        if (!changed.isEmpty()) {
            throw new SnapshotConflictException("trip_has_reservations",
                    "Trip " + tripId + " has holds or bookings, so its date, train, stops, times and fares cannot change.", changed);
        }
    }

    private static boolean sameStops(SnapshotRepository.FrozenFacts stored, TripSnapshot snapshot) {
        if (stored.stops().size() != snapshot.stops().size()) {
            return false;
        }
        for (TripSnapshot.Stop stop : snapshot.stops()) {
            SnapshotRepository.StopFact fact = stored.stops().get(stop.stopIndex());
            // Compared as instants: the same moment written with another offset is not a change.
            if (fact == null || fact.stationId() != stop.stationId()
                    || !Objects.equals(fact.arrival(), instant(stop.arrivalTime()))
                    || !Objects.equals(fact.departure(), instant(stop.departureTime()))) {
                return false;
            }
        }
        return true;
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    private static boolean sameFares(SnapshotRepository.FrozenFacts stored, TripSnapshot snapshot) {
        if (stored.fares().size() != snapshot.fares().size()) {
            return false;
        }
        for (TripSnapshot.Fare fare : snapshot.fares()) {
            BigDecimal old = stored.fares().get(fare.segmentIndex() + "/" + fare.coachClass());
            if (old == null || old.compareTo(fare.amount()) != 0) {
                return false;
            }
        }
        return true;
    }

    /** FR-A9: capacity changes that conflict with holds or bookings are refused, naming what is in the way. */
    private void requireCapacityStillCoversTheReservations(long tripId, TripSnapshot snapshot) {
        List<ApiError.Detail> conflicts = new ArrayList<>();

        Set<Long> reserved = repository.findReservedSeatIds(tripId);
        Map<Long, SnapshotRepository.StoredSeat> stored = repository.findSeats(tripId);
        Map<Long, TripSnapshot.Seat> incoming = new HashMap<>();
        snapshot.seats().forEach(seat -> incoming.put(seat.id(), seat));
        for (Long seatId : reserved) {
            TripSnapshot.Seat seat = incoming.get(seatId);
            String number = stored.get(seatId).seatNumber();
            if (seat == null) {
                conflicts.add(new ApiError.Detail("seats", "seat " + number + " has holds or bookings and cannot be removed"));
            } else if (seat.coachId() != stored.get(seatId).coachId()) {
                conflicts.add(new ApiError.Detail("seats", "seat " + number + " has holds or bookings and cannot move to another coach"));
            }
        }

        Map<Long, String> codes = repository.findCoachCodes(tripId);
        Map<Long, TripSnapshot.Coach> coaches = new HashMap<>();
        snapshot.coaches().forEach(coach -> coaches.put(coach.id(), coach));
        for (Map.Entry<Long, Integer> inUse : repository.findMaxStandingUsed(tripId).entrySet()) {
            TripSnapshot.Coach coach = coaches.get(inUse.getKey());
            String code = codes.get(inUse.getKey());
            if (coach == null) {
                conflicts.add(new ApiError.Detail("coaches", "coach " + code + " has " + inUse.getValue() + " standing places in use and cannot be removed"));
            } else if (coach.standingCapacity() < inUse.getValue()) {
                conflicts.add(new ApiError.Detail("coaches", "coach " + code + " standing capacity cannot go below the " + inUse.getValue() + " places in use"));
            }
        }
        if (!conflicts.isEmpty()) {
            throw new SnapshotConflictException("capacity_conflict", "The change would remove capacity that has holds or bookings.", conflicts);
        }
    }
}
