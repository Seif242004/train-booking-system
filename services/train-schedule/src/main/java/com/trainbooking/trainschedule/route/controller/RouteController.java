package com.trainbooking.trainschedule.route.controller;

import com.trainbooking.trainschedule.route.model.Route;
import com.trainbooking.trainschedule.route.model.RouteRequest;
import com.trainbooking.trainschedule.route.service.RouteService;
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

/** Admin CRUD for routes (SRS 9.2). The ADMIN role is enforced in the security filter chain; no logic lives here. */
@RestController
@RequestMapping("/admin/routes")
public class RouteController {

    private final RouteService service;

    public RouteController(RouteService service) {
        this.service = service;
    }

    @GetMapping
    public List<Route> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Route get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<Route> create(@Valid @RequestBody RouteRequest request, @AuthenticationPrincipal Jwt admin) {
        Route created = service.create(request, admin.getSubject());
        return ResponseEntity.created(URI.create("/admin/routes/" + created.id())).body(created);
    }

    /** {@code clearFares=true} confirms that changing the stops of a route removes its fares. */
    @PutMapping("/{id}")
    public Route update(@PathVariable long id, @Valid @RequestBody RouteRequest request,
                        @RequestParam(defaultValue = "false") boolean clearFares, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, clearFares, admin.getSubject());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
