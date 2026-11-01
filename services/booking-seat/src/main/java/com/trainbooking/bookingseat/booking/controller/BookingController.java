package com.trainbooking.bookingseat.booking.controller;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.PaymentStart;
import com.trainbooking.bookingseat.booking.model.PickRequest;
import com.trainbooking.bookingseat.booking.model.ReleaseRequest;
import com.trainbooking.bookingseat.booking.service.BookingQueryService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.booking.service.PaymentStartService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final PaymentStartService paymentStarts;

    public BookingController(HoldService holds, BookingQueryService queries, PaymentStartService paymentStarts) {
        this.holds = holds;
        this.queries = queries;
        this.paymentStarts = paymentStarts;
    }

    /**
     * {@code POST /bookings/pick} (Flow 3): picks a seat or some standing places. The first pick of the user on the trip starts the hold
     * and its 5 minutes (201); every further pick joins the same booking and never extends the timer (200). 409
     * {@code seats_unavailable} when the pick lost a race: it changes nothing, the seats already held stay held.
     */
    @PostMapping("/bookings/pick")
    public ResponseEntity<BookingView> pick(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PickRequest request) {
        HoldService.PickResult result = holds.pick(AuthenticatedUser.of(jwt), request);
        BookingView booking = result.booking();
        return result.started()
                ? ResponseEntity.created(URI.create("/bookings/" + booking.id())).body(booking)
                : ResponseEntity.ok(booking);
    }

    /**
     * {@code POST /bookings/{id}/release}: deselects a seat or gives back standing places at once. Releasing the last item ends the hold:
     * the answer is then the booking with status ABANDONED.
     */
    @PostMapping("/bookings/{id}/release")
    public BookingView release(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody ReleaseRequest request) {
        return holds.release(AuthenticatedUser.of(jwt), id, request);
    }

    /** {@code DELETE /bookings/{id}/hold} (FR-H7): releases the whole hold at once. */
    @DeleteMapping("/bookings/{id}/hold")
    public BookingView releaseAll(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return holds.releaseAll(AuthenticatedUser.of(jwt), id);
    }

    /**
     * {@code POST /bookings/{id}/payment} (Flow 4, SRS 7.1): "Continue to payment". Only while the hold is active (410 once it ran out); returns
     * the client secret for the embedded Stripe form and the time left. The amount is the one the server worked out, never the client's.
     */
    @PostMapping("/bookings/{id}/payment")
    public PaymentStart startPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return paymentStarts.start(jwt.getSubject(), id);
    }

    /** {@code GET /bookings/{id}}: the booking, if it is the caller's (404 otherwise). The payment page also polls it. */
    @GetMapping("/bookings/{id}")
    public BookingView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return queries.viewOwn(id, jwt.getSubject());
    }
}
