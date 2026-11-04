package com.trainbooking.trainschedule.trip.controller;

import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripRequest;
import com.trainbooking.trainschedule.trip.service.TripService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin CRUD for trips (SRS 9.2). The ADMIN role is enforced in the security filter chain; no logic lives here. */
// Called by the Gateway for /admin/trips (ADMIN only), except the exact paths POST /admin/trips/{id}/cancel and GET .../cancellation,
// which the Gateway sends to Booking &amp; Seat. Errors: 404 trip_not_found, 409 train_inactive / train_has_no_coaches /
// route_fares_incomplete / booking_conflict, 502/503 when Booking &amp; Seat refuses or is down, 400 validation_failed.
// @RestController: JSON in/out. @RequestMapping: common prefix. *Mapping: HTTP method + path. @RequestParam(required = false): optional
// query value. @Valid runs TripRequest's rules; @RequestBody parses the JSON; @AuthenticationPrincipal Jwt = caller's verified token.
@RestController
@RequestMapping("/admin/trips")
public class TripController {

    private final TripService service;

    public TripController(TripService service) {
        this.service = service;
    }

    /** All trips by first departure; {@code date} (yyyy-MM-dd) limits the list to trips leaving on that date. */
    // GET /admin/trips[?date=yyyy-MM-dd]: all trips ordered by first departure, with their stops.
    @GetMapping
    public List<Trip> list(@RequestParam(required = false) LocalDate date) {
        return service.list(date);
    }

    /** GET /admin/trips/{id}: one trip with its stops. 404 trip_not_found. */
    @GetMapping("/{id}")
    public Trip get(@PathVariable long id) {
        return service.get(id);
    }

    /** POST /admin/trips: creates an ACTIVE trip and pushes its snapshot to Booking &amp; Seat before committing. 201 with the trip. */
    @PostMapping
    public ResponseEntity<Trip> create(@Valid @RequestBody TripRequest request, @AuthenticationPrincipal Jwt admin) {
        Trip created = service.create(request, admin.getSubject());
        return ResponseEntity.created(URI.create("/admin/trips/" + created.id())).body(created);
    }

    /** PUT /admin/trips/{id}: replaces train, route, date and all stop times; pushes the snapshot again. Booking may freeze it (409). */
    @PutMapping("/{id}")
    public Trip update(@PathVariable long id, @Valid @RequestBody TripRequest request, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, admin.getSubject());
    }

    /** DELETE /admin/trips/{id}: 204. Booking &amp; Seat is told first; it refuses (409) if the trip has reservations. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
