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

/**
 * Admin CRUD for stations (SRS 9.2). The ADMIN role is enforced in the security filter chain; no logic lives here.
 * Called by the Gateway ({@code /admin/stations/**}) after it checked the token and the role; the service checks them again.
 * Errors (404 station_not_found, 409 station_code_taken / station_in_use, 400 validation_failed) come from {@code GlobalExceptionHandler}.
 */
// @RestController = @Controller + @ResponseBody: methods return objects that Spring writes as JSON.
// @RequestMapping("/admin/stations"): the common URL prefix of every method below.
@RestController
@RequestMapping("/admin/stations")
public class StationController {

    private final StationService service;

    public StationController(StationService service) {
        this.service = service;
    }

    /** GET /admin/stations: every station ordered by code. Response 200: JSON array of stations. */
    // @GetMapping: handles HTTP GET on the class prefix.
    @GetMapping
    public List<Station> list() {
        return service.list();
    }

    /** GET /admin/stations/{id}: one station. 404 station_not_found if the id is unknown. */
    // @GetMapping("/{id}") + @PathVariable: the {id} part of the URL is converted to the long parameter.
    @GetMapping("/{id}")
    public Station get(@PathVariable long id) {
        return service.get(id);
    }

    /**
     * POST /admin/stations: creates a station. Request: {@code { code, name, city }}. Response 201 with the station and a
     * {@code Location} header. 400 if invalid, 409 station_code_taken if the code exists.
     */
    // @PostMapping: HTTP POST. @Valid: run the Bean Validation rules of StationRequest before the method body.
    // @RequestBody: parse the JSON body into the record. @AuthenticationPrincipal Jwt: the verified token of the caller;
    // its subject (sub) is the admin id written to the audit log.
    @PostMapping
    public ResponseEntity<Station> create(@Valid @RequestBody StationRequest request, @AuthenticationPrincipal Jwt admin) {
        Station created = service.create(request, admin.getSubject());
        // A relative Location: the service sits behind the Gateway, so its own host and port must not leak out.
        return ResponseEntity.created(URI.create("/admin/stations/" + created.id())).body(created);
    }

    /** PUT /admin/stations/{id}: replaces code, name and city. 404 unknown id, 409 code taken. Trips that use the station are re-pushed. */
    // @PutMapping: HTTP PUT (full replacement of the resource).
    @PutMapping("/{id}")
    public Station update(@PathVariable long id, @Valid @RequestBody StationRequest request, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, admin.getSubject());
    }

    /** DELETE /admin/stations/{id}: 204 on success, 404 unknown id, 409 station_in_use if a route stops there. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
