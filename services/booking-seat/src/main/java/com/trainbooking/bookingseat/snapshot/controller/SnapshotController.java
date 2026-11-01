package com.trainbooking.bookingseat.snapshot.controller;

import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.snapshot.model.SnapshotResult;
import com.trainbooking.bookingseat.snapshot.model.TripSnapshot;
import com.trainbooking.bookingseat.snapshot.service.SnapshotService;
import com.trainbooking.bookingseat.snapshot.service.SnapshotValidator;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The service-to-service endpoints that Train and Schedule uses to keep Booking's copy of its trips up to date (CLAUDE.md,
 * "Data copy"). They are under /internal, need the shared API key, and are never routed by the Gateway.
 */
@RestController
public class SnapshotController {

    /** Used in the audit log when the caller does not say which admin caused the change. */
    private static final String DEFAULT_ACTOR = "train-schedule";
    /** The length of the admin_id column of the audit log. */
    private static final int ACTOR_MAX = 64;

    private final SnapshotService service;

    public SnapshotController(SnapshotService service) {
        this.service = service;
    }

    /**
     * {@code PUT /internal/trips/{id}/snapshot}: stores the whole snapshot of the trip, or updates the copy that exists.
     * Idempotent: the same snapshot can be pushed again. {@code X-Acting-User} is the Keycloak id of the admin whose action
     * caused the push; it is only written to the audit log.
     */
    @PutMapping("/internal/trips/{id}/snapshot")
    public SnapshotResult put(@PathVariable long id, @Valid @RequestBody TripSnapshot snapshot,
                              @RequestHeader(value = "X-Acting-User", required = false) String actingUser) {
        List<ApiError.Detail> problems = SnapshotValidator.check(snapshot);
        if (!problems.isEmpty()) {
            throw new RequestValidationException(problems);
        }
        return service.apply(id, snapshot, actor(actingUser));
    }

    /** {@code DELETE /internal/trips/{id}}: removes the copy of a trip that was deleted in Train and Schedule. */
    @DeleteMapping("/internal/trips/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @RequestHeader(value = "X-Acting-User", required = false) String actingUser) {
        service.delete(id, actor(actingUser));
        return ResponseEntity.noContent().build();
    }

    private static String actor(String actingUser) {
        if (actingUser == null || actingUser.isBlank()) {
            return DEFAULT_ACTOR;
        }
        return actingUser.length() > ACTOR_MAX ? actingUser.substring(0, ACTOR_MAX) : actingUser;
    }
}
