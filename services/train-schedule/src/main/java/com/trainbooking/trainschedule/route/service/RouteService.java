package com.trainbooking.trainschedule.route.service;

import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.fare.repository.FareRepository;
import com.trainbooking.trainschedule.route.model.Route;
import com.trainbooking.trainschedule.route.model.RouteHasFaresException;
import com.trainbooking.trainschedule.route.model.RouteNotFoundException;
import com.trainbooking.trainschedule.route.model.RouteRequest;
import com.trainbooking.trainschedule.route.model.RouteStop;
import com.trainbooking.trainschedule.route.repository.RouteRepository;
import com.trainbooking.trainschedule.station.service.StationService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Route rules (FR-A8): a route is a name and an ordered list of stations. Changes and audit rows are saved together. */
@Service
public class RouteService {

    private static final String ENTITY = "route";

    private final RouteRepository routes;
    private final FareRepository fares;
    private final StationService stations;
    private final AuditTrail audit;

    public RouteService(RouteRepository routes, FareRepository fares, StationService stations, AuditTrail audit) {
        this.routes = routes;
        this.fares = fares;
        this.stations = stations;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Route> list() {
        return routes.findAll();
    }

    @Transactional(readOnly = true)
    public Route get(long id) {
        return routes.findById(id).orElseThrow(() -> new RouteNotFoundException(id));
    }

    @Transactional
    public Route create(RouteRequest request, String adminId) {
        checkStops(request.stops());
        long id = routes.insert(request.name().strip());
        routes.insertStops(id, request.stops());
        Route created = get(id);
        audit.change(adminId, ENTITY, id, "CREATE", null, created);
        return created;
    }

    /**
     * Updates the name and the stops. Fares belong to segment positions (segment k is the leg from stop k to stop k+1),
     * so when the list of stations changes they no longer describe the same legs. A route that has fares therefore
     * refuses the change unless the caller confirms with {@code clearFares}, in which case the fares are removed.
     * TODO(slice 4): once trips exist, refuse changing the stops of a route that trips use.
     */
    @Transactional
    public Route update(long id, RouteRequest request, boolean clearFares, String adminId) {
        Route before = get(id);
        List<Long> currentStops = before.stops().stream().map(RouteStop::stationId).toList();
        boolean stopsChanged = !currentStops.equals(request.stops());

        int faresCleared = 0;
        if (stopsChanged) {
            checkStops(request.stops());
            int fareCount = fares.countByRoute(id);
            if (fareCount > 0 && !clearFares) {
                throw new RouteHasFaresException(id, fareCount);
            }
            faresCleared = fareCount > 0 ? fares.deleteByRoute(id) : 0;
            routes.deleteStops(id);
            routes.insertStops(id, request.stops());
        }
        if (!routes.updateName(id, request.name().strip())) {
            throw new RouteNotFoundException(id); // deleted by another admin between the read and the update
        }

        Route after = get(id);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("before", before);
        diff.put("after", after);
        if (faresCleared > 0) {
            diff.put("faresCleared", faresCleared);
        }
        audit.record(adminId, ENTITY, id, "UPDATE", diff);
        return after;
    }

    /**
     * Deletes a route together with its stops and fares (database cascade).
     * TODO(slice 4): once trips exist, refuse deleting a route that trips use.
     */
    @Transactional
    public void delete(long id, String adminId) {
        Route before = get(id);
        int fareCount = fares.countByRoute(id); // counted first: the cascade removes the rows
        if (!routes.delete(id)) {
            throw new RouteNotFoundException(id);
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("before", before);
        diff.put("after", null);
        diff.put("cascadedFares", fareCount);
        audit.record(adminId, ENTITY, id, "DELETE", diff);
    }

    /** Every stop must be an existing station, and a route may visit a station only once. Every problem is reported. */
    private void checkStops(List<Long> stops) {
        List<ApiError.Detail> problems = new ArrayList<>();

        Map<Long, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < stops.size(); i++) {
            Integer earlier = firstIndex.putIfAbsent(stops.get(i), i);
            if (earlier != null) {
                problems.add(new ApiError.Detail("stops[" + i + "]",
                        "station " + stops.get(i) + " is already stop " + earlier + "; a route visits a station once"));
            }
        }

        Set<Long> existing = stations.existingIds(firstIndex.keySet());
        for (int i = 0; i < stops.size(); i++) {
            if (!existing.contains(stops.get(i))) {
                problems.add(new ApiError.Detail("stops[" + i + "]", "station " + stops.get(i) + " does not exist"));
            }
        }

        if (!problems.isEmpty()) {
            throw new RequestValidationException(problems);
        }
    }
}
