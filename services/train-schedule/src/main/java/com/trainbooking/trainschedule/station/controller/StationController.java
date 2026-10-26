package com.trainbooking.trainschedule.station.controller;

import com.trainbooking.trainschedule.station.model.Station;
import com.trainbooking.trainschedule.station.model.StationRequest;
import com.trainbooking.trainschedule.station.service.StationService;
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
import org.springframework.web.bind.annotation.RestController;

/** Admin CRUD for stations (SRS 9.2). The ADMIN role is enforced in the security filter chain; no logic lives here. */
@RestController
@RequestMapping("/admin/stations")
public class StationController {

    private final StationService service;

    public StationController(StationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Station> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Station get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<Station> create(@Valid @RequestBody StationRequest request, @AuthenticationPrincipal Jwt admin) {
        Station created = service.create(request, admin.getSubject());
        // A relative Location: the service sits behind the Gateway, so its own host and port must not leak out.
        return ResponseEntity.created(URI.create("/admin/stations/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public Station update(@PathVariable long id, @Valid @RequestBody StationRequest request, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, admin.getSubject());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
