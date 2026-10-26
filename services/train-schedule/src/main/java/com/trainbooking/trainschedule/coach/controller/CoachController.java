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

    @PostMapping("/admin/trains/{trainId}/coaches")
    public ResponseEntity<Coach> create(@PathVariable long trainId, @Valid @RequestBody CoachRequest request,
                                        @AuthenticationPrincipal Jwt admin) {
        Coach created = service.create(trainId, request, admin.getSubject());
        return ResponseEntity.created(URI.create("/admin/trains/" + trainId + "/coaches")).body(created);
    }

    @PutMapping("/admin/coaches/{id}")
    public Coach update(@PathVariable long id, @Valid @RequestBody CoachRequest request, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, admin.getSubject());
    }

    @DeleteMapping("/admin/coaches/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
