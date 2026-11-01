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
