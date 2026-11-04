package com.trainbooking.trainschedule.trip.service;

import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.coach.service.CoachService;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.fare.model.Fare;
import com.trainbooking.trainschedule.fare.service.FareService;
import com.trainbooking.trainschedule.route.model.Route;
import com.trainbooking.trainschedule.route.model.RouteNotFoundException;
import com.trainbooking.trainschedule.route.model.RouteStop;
import com.trainbooking.trainschedule.route.service.RouteService;
import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.model.TrainNotFoundException;
import com.trainbooking.trainschedule.train.model.TrainStatus;
import com.trainbooking.trainschedule.train.service.TrainService;
import com.trainbooking.trainschedule.trip.model.RouteFaresIncompleteException;
import com.trainbooking.trainschedule.trip.model.TrainHasNoCoachesException;
import com.trainbooking.trainschedule.trip.model.TrainInactiveException;
import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripStatus;
import com.trainbooking.trainschedule.trip.model.TripNotFoundException;
import com.trainbooking.trainschedule.trip.model.TripRequest;
import com.trainbooking.trainschedule.trip.repository.TripRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trip rules (FR-A8). Every change and its audit row are written in one transaction. */
// @Service: business-logic bean. Callers: TripController (admin CRUD), InternalTripController (markCancelled), TripLayoutService.
// The key design rule: create, update and delete push the trip to Booking &amp; Seat as their LAST step, inside the same transaction
// (@Transactional = commit on normal return, rollback on a RuntimeException). If Booking refuses or is down the exception rolls back the
// trip
// AND its audit row, so the two services never disagree about whether a trip exists.
@Service
public class TripService {

    private static final String ENTITY = "trip";

    private final TripRepository trips;
    private final TrainService trains;
    private final CoachService coaches;
    private final RouteService routes;
    private final FareService fares;
    /** Builds and pushes the snapshot (see TripSnapshotPublisher); package-private, so only this feature can use it. */
    private final TripSnapshotPublisher snapshots;
    private final AuditTrail audit;
    /** The railway time zone: stop times are entered as local clock times and stored with the offset of this zone. */
    private final ZoneId zone;

    public TripService(TripRepository trips, TrainService trains, CoachService coaches, RouteService routes, FareService fares,
                       TripSnapshotPublisher snapshots, AuditTrail audit, ZoneId zone) {
        this.trips = trips;
        this.trains = trains;
        this.coaches = coaches;
        this.routes = routes;
        this.fares = fares;
        this.snapshots = snapshots;
        this.audit = audit;
        this.zone = zone;
    }

    /** All trips by first departure, optionally only those leaving on one date. */
    // @Transactional(readOnly = true): a read-only database transaction (a hint that no writes happen).
    @Transactional(readOnly = true)
    public List<Trip> list(LocalDate date) {
        return trips.findAll(date);
    }

    /**
     * Booking and Seat tells us that an admin cancelled the whole trip (Flow 8): the trip is marked CANCELLED so it disappears from search.
     * Idempotent (Booking repeats the call until it is accepted). It does NOT push a snapshot back: Booking is the one who asked, and a
     * cancelled trip keeps its copy there.
     */
    @Transactional
    // Idempotent: a second call finds status CANCELLED and returns it unchanged. No snapshot is pushed back (Booking already has it
    // cancelled).
    // The actor is the admin's id passed by Booking in X-Acting-User, or "booking-seat" when absent, so the audit row names who caused it.
    public Trip markCancelled(long id, String actor) {
        Trip before = get(id);
        if (before.status() == TripStatus.CANCELLED) {
            return before;
        }
        trips.markCancelled(id);
        Trip after = get(id);
        audit.change(actor, ENTITY, id, "CANCEL", before, after);
        return after;
    }

    @Transactional(readOnly = true)
    /** One trip with its stops; @throws TripNotFoundException if the id is unknown */
    public Trip get(long id) {
        return trips.findById(id).orElseThrow(() -> new TripNotFoundException(id));
    }

    @Transactional
    /**
      * Creates an ACTIVE trip: checks everything (see plan), saves the trip row and its stops, writes the audit row, then pushes the
      * snapshot.
     * Errors: 400 validation_failed (unknown train or route, stop list or times wrong), 409 train_inactive / train_has_no_coaches /
      * route_fares_incomplete, and from Booking: 409 booking_conflict, 502 booking_rejected_snapshot, 503 booking_unavailable (nothing
      * saved).
     */
    public Trip create(TripRequest request, String adminId) {
        Plan plan = plan(request, true);
        long id = trips.insert(request.trainId(), request.routeId(), request.departureDate());
        trips.insertStops(id, plan.rows());
        Trip created = get(id);
        audit.change(adminId, ENTITY, id, "CREATE", null, created);
        // Last step: Booking and Seat must accept the trip, or this request fails and nothing above is saved.
        snapshots.publish(created, adminId);
        return created;
    }

    /**
     * Replaces the train, route, date and all stop times of a trip, with the same checks as create (the train must be
     * ACTIVE only when it is changed, so that the times of an existing trip can still be corrected). The changed
     * snapshot is pushed to Booking & Seat, which refuses it (409) if it would affect holds or bookings of the trip.
     */
    @Transactional
    public Trip update(long id, TripRequest request, String adminId) {
        Trip before = get(id);
        Plan plan = plan(request, before.trainId() != request.trainId());
        if (!trips.update(id, request.trainId(), request.routeId(), request.departureDate())) {
            throw new TripNotFoundException(id); // deleted by another admin between the read and the update
        }
        trips.deleteStops(id);
        trips.insertStops(id, plan.rows());
        Trip after = get(id);
        audit.change(adminId, ENTITY, id, "UPDATE", before, after);
        // Booking and Seat refuses the change (409) if the trip has holds or bookings, and then nothing is saved.
        snapshots.publish(after, adminId);
        return after;
    }

    /**
     * Deletes a trip and its stops, and asks Booking & Seat to drop its copy. Booking refuses (409) if the trip has holds
     * or bookings: such a trip must be cancelled instead (Flow 8, slice 12).
     */
    @Transactional
    public void delete(long id, String adminId) {
        Trip before = get(id);
        if (!trips.delete(id)) {
            throw new TripNotFoundException(id);
        }
        audit.change(adminId, ENTITY, id, "DELETE", before, null);
        // Booking and Seat refuses (409) if the trip has holds or bookings: such a trip is cancelled, not deleted.
        snapshots.remove(id, adminId);
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** The validated stop rows, ready to save; a record so that plan() can return them as one value. */
    private record Plan(List<TripRepository.StopRow> rows) {
    }

    /** Runs every check of a trip request and returns the stop rows to save. Nothing is saved if any check fails. */
    // Order of the checks: train and route exist (400), train is ACTIVE when required (409), train has coaches (409), stop count matches
    // the
    // route (400), times are valid (400), route fares are complete (409). Only then are the rows built; nothing is written here.
    // The local times become OffsetDateTime with the railway zone, because the database column keeps the offset (Egypt has daylight
    // saving time).
    private Plan plan(TripRequest request, boolean trainMustBeActive) {
        Train train = lookupTrain(request.trainId());
        Route route = lookupRoute(request.routeId());

        if (trainMustBeActive && train.status() != TrainStatus.ACTIVE) {
            throw new TrainInactiveException(train.number());
        }
        List<Coach> trainCoaches = coaches.listByTrain(train.id());
        if (trainCoaches.isEmpty()) {
            throw new TrainHasNoCoachesException(train.number());
        }
        if (request.stops().size() != route.stops().size()) {
            throw new RequestValidationException(List.of(new ApiError.Detail("stops",
                    "must have one entry per stop of the route (" + route.stops().size() + "), in the route's order")));
        }

        List<TripTimeline.Times> times = TripTimeline.resolve(request.departureDate(), request.stops());
        requireCompleteFares(route, trainCoaches);

        List<TripRepository.StopRow> rows = new ArrayList<>();
        for (int i = 0; i < route.stops().size(); i++) {
            TripTimeline.Times t = times.get(i);
            rows.add(new TripRepository.StopRow(i, route.stops().get(i).stationId(),
                    t.arrival() == null ? null : t.arrival().atZone(zone).toOffsetDateTime(),
                    t.departure() == null ? null : t.departure().atZone(zone).toOffsetDateTime()));
        }
        return new Plan(rows);
    }

    /**
     * A ticket must always be priceable: the route needs a fare for every segment and for every class that the train
     * has a coach of. Every missing fare is reported, so the admin can fix them all in one visit to the fares page.
     */
    private void requireCompleteFares(Route route, List<Coach> trainCoaches) {
        Set<CoachClass> classes = EnumSet.noneOf(CoachClass.class);
        trainCoaches.forEach(c -> classes.add(c.coachClass()));

        Set<String> have = new HashSet<>();
        for (Fare fare : fares.list(route.id())) {
            have.add(fare.segmentIndex() + "/" + fare.coachClass());
        }

        List<ApiError.Detail> missing = new ArrayList<>();
        List<RouteStop> stops = route.stops();
        for (int segment = 0; segment < stops.size() - 1; segment++) {
            for (CoachClass coachClass : classes) {
                if (!have.contains(segment + "/" + coachClass)) {
                    missing.add(new ApiError.Detail("fares", "segment " + segment + " (" + stops.get(segment).stationName() + " to "
                            + stops.get(segment + 1).stationName() + ") has no " + coachClass + " fare"));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new RouteFaresIncompleteException(missing);
        }
    }

    /** Wraps "not found" into a 400 on the field trainId: for a request body an unknown id is bad input, not a missing URL (404). */
    private Train lookupTrain(long id) {
        try {
            return trains.get(id);
        } catch (TrainNotFoundException e) {
            throw new RequestValidationException(List.of(new ApiError.Detail("trainId", "train " + id + " does not exist")));
        }
    }

    /** Same as lookupTrain, for routeId. */
    private Route lookupRoute(long id) {
        try {
            return routes.get(id);
        } catch (RouteNotFoundException e) {
            throw new RequestValidationException(List.of(new ApiError.Detail("routeId", "route " + id + " does not exist")));
        }
    }
}
