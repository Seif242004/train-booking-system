package com.trainbooking.trainschedule.trip.service;

import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.service.CoachService;
import com.trainbooking.trainschedule.fare.service.FareService;
import com.trainbooking.trainschedule.seat.model.Seat;
import com.trainbooking.trainschedule.seat.service.SeatService;
import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.service.TrainService;
import com.trainbooking.trainschedule.trip.model.BookingSnapshot;
import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripStop;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Hands a trip over to Booking and Seat (CLAUDE.md, "Data copy"): builds the immutable snapshot (stops and times, coaches
 * with standing capacity, seats, fares) and pushes it, or asks Booking to drop the copy of a deleted trip.
 *
 * <p>The push is <b>synchronous and part of the transaction of the admin request</b>: {@code TripService} calls it as its
 * last step, so a failure makes the request roll back and the trip is committed only after Booking has accepted the
 * snapshot. (The outbox with a retry poller is for the calls that do not need an answer before they commit, such as
 * the emails.) Because Booking applies a snapshot idempotently, the admin simply repeats the request after a failure.
 * The one gap is Booking accepting the push and the commit here then failing; Booking would hold a copy of a trip that
 * does not exist, which nothing offers to passengers, and a later push of the same trip id would overwrite it.
 */
// @Component: a plain Spring bean, package-private. Callers: TripService (create/update/delete) and TripSnapshotRefresher (edits of
// trains etc.).
@Component
class TripSnapshotPublisher {

    private final TrainService trains;
    private final CoachService coaches;
    private final SeatService seats;
    private final FareService fares;
    /** The HTTP client for Booking &amp; Seat. */
    private final BookingSeatClient booking;
    /** The railway time zone, used to give the local stop times back their offset. */
    private final ZoneId zone;

    TripSnapshotPublisher(TrainService trains, CoachService coaches, SeatService seats, FareService fares,
                          BookingSeatClient booking, ZoneId zone) {
        this.trains = trains;
        this.coaches = coaches;
        this.seats = seats;
        this.fares = fares;
        this.booking = booking;
        this.zone = zone;
    }

    /** Builds the snapshot of the trip as it is now and pushes it. Throws if Booking does not accept it. */
    void publish(Trip trip, String adminId) {
        booking.putSnapshot(trip.id(), build(trip), adminId);
    }

    /** Asks Booking to drop the copy of the trip. Throws if the trip has holds or bookings there. */
    void remove(long tripId, String adminId) {
        booking.deleteTrip(tripId, adminId);
    }

    /**
     * Builds the immutable snapshot of a trip from the CURRENT data: train, the coaches in train order, every seat of every coach, and the
     * fares of the route. Not cached on purpose: every push describes the state at that moment, and Booking applies it with MERGE.
     */
    BookingSnapshot build(Trip trip) {
        Train train = trains.get(trip.trainId());
        List<Coach> trainCoaches = coaches.listByTrain(trip.trainId());

        List<BookingSnapshot.Stop> stops = trip.stops().stream().map(this::stop).toList();
        List<BookingSnapshot.Coach> coachCopies = trainCoaches.stream()
                .map(c -> new BookingSnapshot.Coach(c.id(), c.code(), c.coachClass(), c.position(), c.standingCapacity())).toList();
        List<BookingSnapshot.Seat> seatCopies = new ArrayList<>();
        for (Coach coach : trainCoaches) {
            for (Seat seat : seats.listByCoach(coach.id())) {
                seatCopies.add(new BookingSnapshot.Seat(seat.id(), seat.coachId(), seat.seatNumber(), seat.row(), seat.column(), seat.coachClass()));
            }
        }
        List<BookingSnapshot.Fare> fareCopies = fares.list(trip.routeId()).stream()
                .map(f -> new BookingSnapshot.Fare(f.segmentIndex(), f.coachClass(), f.amount(), f.currency())).toList();

        return new BookingSnapshot(trip.departureDate(), new BookingSnapshot.Train(train.number(), train.name(), train.type()),
                trip.routeName(), stops, coachCopies, seatCopies, fareCopies);
    }

    private BookingSnapshot.Stop stop(TripStop stop) {
        return new BookingSnapshot.Stop(stop.stopIndex(), stop.stationId(), stop.stationCode(), stop.stationName(),
                offset(stop.arrivalTime()), offset(stop.departureTime()));
    }

    /** The stop times are local date-times in the service zone; Booking gets them with their offset. */
    private OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(zone).toOffsetDateTime();
    }
}
