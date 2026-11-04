package com.trainbooking.trainschedule.fare.service;

import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.common.SnapshotChanges;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.ListValidation;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.fare.model.Fare;
import com.trainbooking.trainschedule.fare.model.FareRequest;
import com.trainbooking.trainschedule.fare.repository.FareRepository;
import com.trainbooking.trainschedule.route.model.Route;
import com.trainbooking.trainschedule.route.service.RouteService;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fares per route segment per coach class, in EGP (CLAUDE.md, "Fares"). Set as a whole, all or nothing. */
// @Service: business-logic bean. Money rule: amounts are BigDecimal in EGP with exactly 2 decimals; the server sets the currency, the
// client cannot.
@Service
public class FareService {

    /** 3 classes on at most 49 segments (a route has at most 50 stops). */
    static final int MAX_FARES_PER_REQUEST = 150;

    private final FareRepository fares;
    private final RouteService routes;
    /** Jakarta Bean Validation validator, used by hand to validate each fare of the list. */
    private final Validator validator;
    private final AuditTrail audit;
    private final SnapshotChanges changes;

    public FareService(FareRepository fares, RouteService routes, Validator validator, AuditTrail audit, SnapshotChanges changes) {
        this.fares = fares;
        this.routes = routes;
        this.validator = validator;
        this.audit = audit;
        this.changes = changes;
    }

    /** The fares of a route, by segment then class. 404 if the route does not exist. */
    @Transactional(readOnly = true)
    public List<Fare> list(long routeId) {
        routes.get(routeId);
        return fares.findByRoute(routeId);
    }

    /**
     * Replaces the whole set of fares of a route with the given list. The list is checked completely first: every fare
     * valid, every segment on this route (0 to stops-2), and each segment and class only once. Any problem rejects the
     * request and the existing fares are left as they were. An empty list is not allowed; to remove fares, change the
     * route's stops with clearFares or delete the route.
     * A segment or class may be left without a fare here; creating a trip requires a fare for every segment and every class the train has.
     */
    @Transactional
    // Replace = delete the route's old fares, insert the new list, all in this transaction: if anything fails, the old fares stay.
    public List<Fare> replaceAll(long routeId, List<FareRequest> requests, String adminId) {
        Route route = routes.get(routeId); // 404 if the route does not exist
        ListValidation.require(validator, requests, "fares", MAX_FARES_PER_REQUEST);
        List<Fare> wanted = checkAgainstRoute(requests, route.stops().size() - 1);

        List<Fare> before = fares.findByRoute(routeId);
        fares.deleteByRoute(routeId);
        fares.insertAll(routeId, wanted);
        List<Fare> after = fares.findByRoute(routeId);

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("before", before);
        diff.put("after", after);
        audit.record(adminId, "route", routeId, "SET_FARES", diff);
        // Fares are frozen once a trip has reservations: the push of the changed fares to every future trip on the route
        // is refused (409) by Booking and Seat if any of them has holds or bookings, and then nothing is saved.
        changes.routeChanged(routeId, adminId);
        return after;
    }

    /** Segment range and repeats, which need to know the route. Returns the fares in EGP with two decimals. */
    // segments = stops - 1 (a route with 5 stops has segments 0 to 3). A fare for a segment beyond that is rejected.
    private static List<Fare> checkAgainstRoute(List<FareRequest> requests, int segments) {
        List<ApiError.Detail> problems = new ArrayList<>();
        List<Fare> result = new ArrayList<>();
        Map<String, Integer> firstSeen = new HashMap<>();

        for (int i = 0; i < requests.size(); i++) {
            FareRequest request = requests.get(i);
            if (request.segmentIndex() >= segments) {
                problems.add(new ApiError.Detail("[" + i + "].segmentIndex",
                        "this route has " + segments + " segments, numbered 0 to " + (segments - 1)));
            }
            Integer earlier = firstSeen.putIfAbsent(request.segmentIndex() + "/" + request.coachClass(), i);
            if (earlier != null) {
                problems.add(new ApiError.Detail("[" + i + "].class",
                        "segment " + request.segmentIndex() + " " + request.coachClass() + " is repeated (also at position " + earlier + ")"));
            }
            result.add(new Fare(request.segmentIndex(), CoachClass.valueOf(request.coachClass()),
                    request.amount().setScale(2), Fare.EGP));
        }
        if (!problems.isEmpty()) {
            throw new RequestValidationException(problems);
        }
        return result;
    }
}
