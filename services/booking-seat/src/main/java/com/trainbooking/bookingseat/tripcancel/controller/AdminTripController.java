package com.trainbooking.bookingseat.tripcancel.controller;

import com.trainbooking.bookingseat.booking.model.CancelRequest;
import com.trainbooking.bookingseat.tripcancel.model.TripCancellationStatus;
import com.trainbooking.bookingseat.tripcancel.service.TripCancellationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Whole-trip cancellation (Flow 8). Everything under {@code /admin} needs the ADMIN role, at the Gateway and here. */
@RestController
public class AdminTripController {

    private final TripCancellationService service;

    public AdminTripController(TripCancellationService service) {
        this.service = service;
    }

    /**
     * {@code POST /admin/trips/{id}/cancel}: cancels the whole trip and refunds every booking, with the reason. Safe to repeat: it does another pass
     * over what is left. The answer says where it stands; {@code status} CANCELLING is shown as "CANCELLED - bookings pending".
     */
    @PostMapping("/admin/trips/{id}/cancel")
    public TripCancellationStatus cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody CancelRequest request) {
        return service.request(jwt.getSubject(), id, request.reason().trim());
    }

    /** {@code GET /admin/trips/{id}/cancellation}: where the cancellation stands (OPEN when the trip is not being cancelled). */
    @GetMapping("/admin/trips/{id}/cancellation")
    public TripCancellationStatus status(@PathVariable long id) {
        return service.status(id);
    }
}
