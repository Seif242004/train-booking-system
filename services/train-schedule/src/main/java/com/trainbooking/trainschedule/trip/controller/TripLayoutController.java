package com.trainbooking.trainschedule.trip.controller;

import com.trainbooking.trainschedule.trip.model.TripLayout;
import com.trainbooking.trainschedule.trip.service.TripLayoutService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The layout of a trip (SRS 9.1): coaches, seats and standing capacity. Any signed-in user may read it. It is called by the
 * Gateway, which composes the seat map from it and from the availability of Booking and Seat; the browser does not call it.
 */
@RestController
public class TripLayoutController {

    private final TripLayoutService service;

    public TripLayoutController(TripLayoutService service) {
        this.service = service;
    }

    @GetMapping("/trips/{id}/layout")
    public TripLayout layout(@PathVariable long id) {
        return service.layout(id);
    }
}
