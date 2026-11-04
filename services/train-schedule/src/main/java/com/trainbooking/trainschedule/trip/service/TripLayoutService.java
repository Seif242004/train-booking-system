package com.trainbooking.trainschedule.trip.service;

import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.service.CoachService;
import com.trainbooking.trainschedule.seat.service.SeatService;
import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.service.TrainService;
import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripLayout;
import com.trainbooking.trainschedule.trip.model.TripNotFoundException;
import com.trainbooking.trainschedule.trip.repository.TripRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The layout of a trip for the seat map (SRS 9.1). Read only: the coaches come in train order, the seats in seat-map order. */
// @Service: business-logic bean. Reuses TrainService, CoachService and SeatService (their reads are transactional too), so the layout is
// built from the same rules as the admin screens. Caller: TripLayoutController (via the Gateway's seat-map composition).
@Service
public class TripLayoutService {

    private final TripRepository trips;
    private final TrainService trains;
    private final CoachService coaches;
    private final SeatService seats;

    public TripLayoutService(TripRepository trips, TrainService trains, CoachService coaches, SeatService seats) {
        this.trips = trips;
        this.trains = trains;
        this.coaches = coaches;
        this.seats = seats;
    }

    /**
      * Builds the layout of one trip: trip stops with times, then each coach (train order) with its seats (seat-map order) and standing
      * capacity.
     * No availability here (Booking &amp; Seat owns it). Cost: one seat query per coach.
     *
     * @throws TripNotFoundException if the trip does not exist (404)
     */
    // @Transactional(readOnly = true): one read-only transaction so all the reads see the same data.
    @Transactional(readOnly = true)
    public TripLayout layout(long tripId) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        Train train = trains.get(trip.trainId());
        List<Coach> trainCoaches = coaches.listByTrain(trip.trainId()); // ordered by position
        List<TripLayout.LayoutCoach> layoutCoaches = trainCoaches.stream()
                .map(coach -> new TripLayout.LayoutCoach(coach.id(), coach.code(), coach.coachClass(), coach.position(), coach.standingCapacity(),
                        seats.listByCoach(coach.id()).stream()
                                .map(seat -> new TripLayout.LayoutSeat(seat.id(), seat.seatNumber(), seat.row(), seat.column(), seat.coachClass()))
                                .toList()))
                .toList();
        return new TripLayout(trip.id(), trip.status(), trip.departureDate(), train.number(), train.name(), train.type(),
                trip.routeName(), trip.stops(), layoutCoaches);
    }
}
