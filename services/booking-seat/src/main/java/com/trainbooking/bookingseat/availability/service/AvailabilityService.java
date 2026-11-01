package com.trainbooking.bookingseat.availability.service;

import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.availability.model.TripAvailability;
import com.trainbooking.bookingseat.availability.model.TripHeader;
import com.trainbooking.bookingseat.availability.repository.AvailabilityRepository;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Availability of a trip for a journey (FR-S2, FR-S4, FR-S5): the data of the search results and of the seat map. */
@Service
public class AvailabilityService {

    private final AvailabilityRepository repository;

    public AvailabilityService(AvailabilityRepository repository) {
        this.repository = repository;
    }

    /**
     * Read-only transaction: Oracle then gives all the queries below one consistent snapshot, so a hold that commits
     * in between cannot show up in the seats but be missing from the standing counters (or the reverse).
     */
    @Transactional(readOnly = true)
    public TripAvailability availability(long tripId, int fromStop, int toStop) {
        TripHeader trip = repository.findTrip(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        Journey journey = Journey.of(fromStop, toStop, trip.stopCount());
        var result = SegmentAvailability.compute(journey, repository.findSeats(tripId),
                repository.findReservations(tripId, journey), repository.findStanding(tripId));
        return new TripAvailability(tripId, trip.status(), result, JourneyFares.perClass(journey, repository.findFares(tripId)));
    }
}
