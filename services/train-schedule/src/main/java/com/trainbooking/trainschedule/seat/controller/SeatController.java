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
@RestController
public class SeatController {

    private final SeatService service;

    public SeatController(SeatService service) {
        this.service = service;
    }

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

    @PutMapping("/admin/seats/{id}")
    public Seat update(@PathVariable long id, @Valid @RequestBody SeatRequest request, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, admin.getSubject());
    }

    @DeleteMapping("/admin/seats/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
