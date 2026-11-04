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
// Called by the Gateway for /admin/trains (ADMIN only). Errors: 404 train_not_found, 409 train_number_taken / train_in_use, 400 validation.
// @RestController: methods return objects that Spring writes as JSON. @RequestMapping: the common URL prefix.
@RestController
@RequestMapping("/admin/trains")
public class TrainController {

    private final TrainService service;

    public TrainController(TrainService service) {
        this.service = service;
    }

    /** Optional filters: {@code number} and {@code name} (contains, any case) and {@code status} (ACTIVE or INACTIVE). */
    // GET /admin/trains?number=&name=&status= : 200 JSON array ordered by number. A bad status value answers 400 validation_failed.
    // @GetMapping: HTTP GET. @RequestParam(required = false): optional query-string value, null when absent.
    @GetMapping
    public List<Train> list(@RequestParam(required = false) String number,
                            @RequestParam(required = false) String name,
                            @RequestParam(required = false) String status) {
        return service.list(number, name, status);
    }

    /** GET /admin/trains/{id}: one train, 404 train_not_found otherwise. @PathVariable binds the {id} part of the URL. */
    @GetMapping("/{id}")
    public Train get(@PathVariable long id) {
        return service.get(id);
    }

    /**
     * POST /admin/trains: request {@code { number, name, type, status }}, response 201 with the train and a relative Location header.
     * 400 validation_failed, 409 train_number_taken. @Valid runs the rules of TrainRequest; @RequestBody parses the JSON;
     * @AuthenticationPrincipal Jwt gives the caller's verified token, whose subject is the admin id in the audit log.
     */
    @PostMapping
    public ResponseEntity<Train> create(@Valid @RequestBody TrainRequest request, @AuthenticationPrincipal Jwt admin) {
        Train created = service.create(request, admin.getSubject());
        return ResponseEntity.created(URI.create("/admin/trains/" + created.id())).body(created);
    }

    /** PUT /admin/trains/{id}: replaces all four fields. 404, 409 train_number_taken. Trips of the train are re-pushed to Booking. */
    @PutMapping("/{id}")
    public Train update(@PathVariable long id, @Valid @RequestBody TrainRequest request, @AuthenticationPrincipal Jwt admin) {
        return service.update(id, request, admin.getSubject());
    }

    /** DELETE /admin/trains/{id}: 204. Removes the coaches and seats too. 409 train_in_use if any trip uses the train. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt admin) {
        service.delete(id, admin.getSubject());
        return ResponseEntity.noContent().build();
    }
}
