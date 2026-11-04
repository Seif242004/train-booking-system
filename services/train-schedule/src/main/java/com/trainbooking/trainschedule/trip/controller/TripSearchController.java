package com.trainbooking.trainschedule.trip.controller;

import com.trainbooking.trainschedule.trip.model.TripSearchResponse;
import com.trainbooking.trainschedule.trip.service.TripSearchService;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Trip search for passengers (SRS 9.1): any signed-in user, not only admins. */
// @RestController: JSON out. Called by the Gateway for the exact path GET /trips (which then adds seats-left from Booking).
@RestController
public class TripSearchController {

    private final TripSearchService service;

    public TripSearchController(TripSearchService service) {
        this.service = service;
    }

    /**
     * {@code GET /trips?from=CAI&to=ALX&date=2026-11-02}: from and to are station codes, date is yyyy-MM-dd. Cancelled trips
     * are not returned, and only trips where the origin comes before the destination on the route. The answer is
     * {@code { trips: [...], nextAvailableDate? }}; the date is present only when {@code trips} is empty and a later trip exists.
     */
    // @RequestParam: a required query value (a missing one answers 400 missing_parameter); a bad date answers 400 invalid_parameter.
    // An unknown station code is not an error: the answer is an empty trip list.
    @GetMapping("/trips")
    public TripSearchResponse search(@RequestParam String from, @RequestParam String to, @RequestParam LocalDate date) {
        return service.search(from, to, date);
    }
}
