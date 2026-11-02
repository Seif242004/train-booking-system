package com.trainbooking.bookingseat.booking.controller;

import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.CancelRequest;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Support for the passenger (Flow 6, way B, Flow 7). Everything under {@code /admin} needs the ADMIN role, here as well as at the Gateway. */
@RestController
public class AdminBookingController {

    private final CancellationService cancellations;

    public AdminBookingController(CancellationService cancellations) {
        this.cancellations = cancellations;
    }

    /**
     * {@code POST /admin/bookings/{id}/cancel}: cancels a confirmed booking on the passenger's request, with the reason, and refunds it in full.
     * It is the same cancellation as the passenger's, and records which admin did it and why.
     */
    @PostMapping("/admin/bookings/{id}/cancel")
    public BookingView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody CancelRequest request) {
        return cancellations.cancel(CancellationService.Actor.admin(jwt.getSubject()), id, request.reason().trim());
    }
}
