package com.trainbooking.trainschedule.trip.controller;

import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.service.TripService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service endpoint for Booking and Seat (shared API key in {@code X-Internal-Api-Key}): never reached by a user. */
@RestController
public class InternalTripController {

    private final TripService service;

    public InternalTripController(TripService service) {
        this.service = service;
    }

    /**
     * {@code POST /internal/trips/{id}/cancel} (Flow 8, step 3): marks the trip CANCELLED so it leaves the search results. Repeating the call is
     * safe. {@code X-Acting-User} (optional) is the admin who cancelled the trip, for the audit log.
     */
    @PostMapping("/internal/trips/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id, @RequestHeader(value = "X-Acting-User", required = false) String actor) {
        Trip trip = service.markCancelled(id, actor == null || actor.isBlank() ? "booking-seat" : actor);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", trip.id());
        body.put("status", trip.status().name());
        return body;
    }
}
