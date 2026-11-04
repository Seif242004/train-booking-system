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
// Called by the Gateway for /admin/routes (ADMIN only). Errors: 404 route_not_found, 409 route_has_fares / route_in_use, 400
// validation_failed.
// @RestController: return values are written as JSON. @RequestMapping: common URL prefix.
// @GetMapping/@PostMapping/@PutMapping/@DeleteMapping:
// HTTP method + path. @PathVariable binds {id}; @Valid runs RouteRequest's rules; @RequestBody parses the JSON; @AuthenticationPrincipal
// Jwt is
// the caller's verified token (subject = admin id for the audit log).
@RestController
@RequestMapping("/admin/routes")
public class RouteController {

    private final RouteService service;

    public RouteController(RouteService service) {
        this.service = service;
    }

    /** GET /admin/routes: every route with its stops in travel order, ordered by name. */
    @GetMapping
    public List<Route> list() {
        return service.list();
    }

    /** GET /admin/routes/{id}: one route with its stops. 404 route_not_found. */
    @GetMapping("/{id}")
    public Route get(@PathVariable long id) {
        return service.get(id);
    }

    /**
     * POST /admin/routes: request {@code { name, stops: [stationId, ...] }}; 201 with the route (stops include station code, name, city).
     */
    @PostMapping
    public ResponseEntity<Route> create(@Valid @RequestBody RouteRequest request, @AuthenticationPrincipal Jwt admin) {
        Route created = service.create(request, admin.getSubject());
        return ResponseEntity.created(URI.create("/admin/routes/" + created.id())).body(created);
    }

    // PUT /admin/routes/{id}[?clearFares=true]: replaces name and stops. @RequestParam(defaultValue = "false") reads the optional query
    // flag.
    // 409 route_has_fares if the stops change while fares exist and the flag is missing; 409 route_in_use if trips use the route.
    /** {@code clearFares=true} confirms that changing the stops of a route removes its fares. */
    @PutMapping("/{id}")
    public Route update(@PathVariable long id, @Valid @RequestBody RouteRequest request,
                        @RequestParam(defaultValue = "false") boolean clearFares, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, clearFares, admin.getSubject());
    }

    /** DELETE /admin/routes/{id}: 204. Stops and fares go with it. 409 route_in_use if trips use it. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
