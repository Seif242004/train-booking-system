package com.trainbooking.bookingseat.booking.controller;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.service.BookingQueryService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The passenger's bookings (SRS 9.1). The caller is the Keycloak user of the token; nothing else identifies them. */
@RestController
public class BookingController {

    private final HoldService holds;
    private final BookingQueryService queries;

    public BookingController(HoldService holds, BookingQueryService queries) {
        this.holds = holds;
        this.queries = queries;
    }

    /**
     * {@code POST /bookings}: holds the seats and standing places for the journey, all or nothing, and returns the booking
     * (PENDING_PAYMENT) with the time its hold runs out. 409 {@code seats_unavailable} lists the items that were lost.
     */
    @PostMapping("/bookings")
    public ResponseEntity<BookingView> hold(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HoldRequest request) {
        BookingView booking = holds.hold(AuthenticatedUser.of(jwt), request);
        return ResponseEntity.created(URI.create("/bookings/" + booking.id())).body(booking);
    }

    /** {@code GET /bookings/{id}}: the booking, if it is the caller's (404 otherwise). The payment page also polls it. */
    @GetMapping("/bookings/{id}")
    public BookingView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return queries.viewOwn(id, jwt.getSubject());
    }
}
