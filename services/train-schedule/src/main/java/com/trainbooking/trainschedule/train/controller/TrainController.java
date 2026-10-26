package com.trainbooking.trainschedule.train.controller;

import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.model.TrainRequest;
import com.trainbooking.trainschedule.train.service.TrainService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin CRUD for trains (SRS 9.2). The ADMIN role is enforced in the security filter chain; no logic lives here. */
@RestController
@RequestMapping("/admin/trains")
public class TrainController {

    private final TrainService service;

    public TrainController(TrainService service) {
        this.service = service;
    }

    /** Optional filters: {@code number} and {@code name} (contains, any case) and {@code status} (ACTIVE or INACTIVE). */
    @GetMapping
    public List<Train> list(@RequestParam(required = false) String number,
                            @RequestParam(required = false) String name,
                            @RequestParam(required = false) String status) {
        return service.list(number, name, status);
    }

    @GetMapping("/{id}")
    public Train get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<Train> create(@Valid @RequestBody TrainRequest request, @AuthenticationPrincipal Jwt admin) {
        Train created = service.create(request, admin.getSubject());
        return ResponseEntity.created(URI.create("/admin/trains/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public Train update(@PathVariable long id, @Valid @RequestBody TrainRequest request, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, admin.getSubject());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
