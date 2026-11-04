package com.trainbooking.trainschedule.seat.controller;

import com.trainbooking.trainschedule.seat.model.Seat;
import com.trainbooking.trainschedule.seat.model.SeatRequest;
import com.trainbooking.trainschedule.seat.service.SeatService;
import jakarta.validation.Valid;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RestController;

/** Admin API for the seats of a coach (SRS 9.2). The ADMIN role is enforced in the security filter chain. */
// Called by the Gateway for /admin/coaches/{id}/seats and /admin/seats/{id} (ADMIN only). Errors: 404 coach_not_found / seat_not_found,
// 400 validation_failed, 409 seat_conflict. No logic here: the service validates and saves.
// @RestController: return values are written as JSON. The *Mapping annotations give the HTTP method and path; @PathVariable binds a
// {name} of the URL; @RequestBody parses the JSON body; @Valid runs the rules of SeatRequest; @AuthenticationPrincipal Jwt is the caller's
// verified token (its subject is the admin id in the audit log).
@RestController
public class SeatController {

    private final SeatService service;

    public SeatController(SeatService service) {
        this.service = service;
    }

    /** GET /admin/coaches/{coachId}/seats: the coach's seats in seat-map order (row, then column). 404 coach_not_found. */
    @GetMapping("/admin/coaches/{coachId}/seats")
    public List<Seat> list(@PathVariable long coachId) {
        return service.listByCoach(coachId);
    }

    /**
     * Bulk create: the body is a JSON list of {@code {seatNumber, row, column}}, saved all or nothing. The list is
     * validated by the service (not by @Valid) so that every error can name the position of the seat in the list.
     */
    @PostMapping("/admin/coaches/{coachId}/seats")
    public ResponseEntity<List<Seat>> create(@PathVariable long coachId, @RequestBody List<SeatRequest> request,
                                             @AuthenticationPrincipal Jwt admin) {
        List<Seat> created = service.addSeats(coachId, request, admin.getSubject());
        return ResponseEntity.created(URI.create("/admin/coaches/" + coachId + "/seats")).body(created);
    }

    /** PUT /admin/seats/{id}: replaces seatNumber, row and column (the class never changes here). 409 seat_conflict on a clash. */
    @PutMapping("/admin/seats/{id}")
    public Seat update(@PathVariable long id, @Valid @RequestBody SeatRequest request, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, admin.getSubject());
    }

    /**
     * DELETE /admin/seats/{id}: 204. Booking &amp; Seat may refuse (409 booking_conflict) if the seat has holds or bookings on a future
     * trip.
     */
    @DeleteMapping("/admin/seats/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
