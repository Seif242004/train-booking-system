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
@RestController
@RequestMapping("/admin/trips")
public class TripController {

    private final TripService service;

    public TripController(TripService service) {
        this.service = service;
    }

    /** All trips by first departure; {@code date} (yyyy-MM-dd) limits the list to trips leaving on that date. */
    @GetMapping
    public List<Trip> list(@RequestParam(required = false) LocalDate date) {
        return service.list(date);
    }

    @GetMapping("/{id}")
    public Trip get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<Trip> create(@Valid @RequestBody TripRequest request, @AuthenticationPrincipal Jwt admin) {
        Trip created = service.create(request, admin.getSubject());
        return ResponseEntity.created(URI.create("/admin/trips/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public Trip update(@PathVariable long id, @Valid @RequestBody TripRequest request, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, admin.getSubject());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
