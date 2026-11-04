package com.trainbooking.trainschedule.coach.controller;

import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.model.CoachRequest;
import com.trainbooking.trainschedule.coach.service.CoachService;
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

/** Admin API for the coaches of a train (SRS 9.2). The ADMIN role is enforced in the security filter chain. */
// Called by the Gateway for /admin/trains/{id}/coaches and /admin/coaches/{id} (ADMIN only). Errors: 404 train_not_found /
// coach_not_found, 409 coach_code_taken / coach_position_taken, 400 validation_failed.
// @RestController: methods return objects written as JSON. @GetMapping/@PostMapping/@PutMapping/@DeleteMapping: the HTTP method and path.
// @PathVariable binds a {name} part of the URL; @Valid runs the rules of CoachRequest; @RequestBody parses the JSON body;
// @AuthenticationPrincipal Jwt is the caller's verified token (its subject is the admin id for the audit log).
@RestController
public class CoachController {

    private final CoachService service;

    public CoachController(CoachService service) {
        this.service = service;
    }

    /** The coaches of the train, ordered by their position in the train. */
    @GetMapping("/admin/trains/{trainId}/coaches")
    public List<Coach> list(@PathVariable long trainId) {
        return service.listByTrain(trainId);
    }

    /**
     * POST /admin/trains/{trainId}/coaches: adds a coach {@code { code, class, position, standingCapacity }}; 201, a relative Location
     * header.
     */
    @PostMapping("/admin/trains/{trainId}/coaches")
    public ResponseEntity<Coach> create(@PathVariable long trainId, @Valid @RequestBody CoachRequest request,
                                        @AuthenticationPrincipal Jwt admin) {
        Coach created = service.create(trainId, request, admin.getSubject());
        return ResponseEntity.created(URI.create("/admin/trains/" + trainId + "/coaches")).body(created);
    }

    /** PUT /admin/coaches/{id}: replaces the coach's fields. The class of its seats follows a class change. */
    @PutMapping("/admin/coaches/{id}")
    public Coach update(@PathVariable long id, @Valid @RequestBody CoachRequest request, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, admin.getSubject());
    }

    /** DELETE /admin/coaches/{id}: 204. The coach's seats are deleted with it (database cascade). */
    @DeleteMapping("/admin/coaches/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
