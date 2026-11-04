package com.trainbooking.bookingseat.booking.controller;

import com.trainbooking.bookingseat.booking.model.AdminBookingView;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.CancelRequest;
import com.trainbooking.bookingseat.booking.model.IssueRequest;
import com.trainbooking.bookingseat.booking.service.BookingIssueService;
import com.trainbooking.bookingseat.booking.service.AdminBookingQueryService;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Support for the passenger (Flow 6, way B, Flow 7). Everything under {@code /admin} needs the ADMIN role, here as well as at the Gateway. */
@RestController
public class AdminBookingController {

    private final CancellationService cancellations;
    private final AdminBookingQueryService queries;
    private final BookingIssueService issues;

    public AdminBookingController(CancellationService cancellations, AdminBookingQueryService queries, BookingIssueService issues) {
        this.cancellations = cancellations;
        this.queries = queries;
        this.issues = issues;
    }

    /**
     * {@code GET /admin/bookings?ref=&email=&tripId=&status=&refundStatus=}: support looks a booking up (Flow 7). Every filter is optional; use
     * {@code refundStatus=REFUND_FAILED} for the refunds that need a follow-up. At most 100, newest first.
     */
    @GetMapping("/admin/bookings")
    public java.util.List<AdminBookingView> search(@RequestParam(required = false) String ref, @RequestParam(required = false) String email,
                                                   @RequestParam(required = false) Long tripId, @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String refundStatus,
                                                   @RequestParam(required = false) String method) {
        return queries.search(ref, email, tripId, status, refundStatus, method);
    }

    /** {@code GET /admin/passengers/lookup?q=}: the one passenger with exactly this email (id, name, email), or 404. Never a list. */
    @GetMapping("/admin/passengers/lookup")
    public java.util.Map<String, String> lookup(@RequestParam String q) {
        var found = issues.lookup(q).orElseThrow(com.trainbooking.bookingseat.booking.model.PassengerNotFoundException::new);
        return java.util.Map.of("id", found.userId(), "name", found.name() == null || found.name().isBlank() ? found.email() : found.name(), "email", found.email());
    }

    /** {@code POST /admin/bookings/{id}/issue}: confirms the admin's own hold without Stripe (counter or complimentary). */
    @PostMapping("/admin/bookings/{id}/issue")
    public BookingView issue(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody IssueRequest request) {
        return issues.issue(jwt.getSubject(), id, request);
    }

    /** {@code POST /admin/bookings/{id}/refunded-at-counter}: the money of a cancelled counter booking was returned at the counter. */
    @PostMapping("/admin/bookings/{id}/refunded-at-counter")
    public BookingView refundedAtCounter(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return issues.markRefundedAtCounter(jwt.getSubject(), id);
    }

    /** {@code GET /admin/bookings/{id}}: the booking with the passenger, the payment and refund status, and who cancelled it. */
    @GetMapping("/admin/bookings/{id}")
    public AdminBookingView details(@PathVariable long id) {
        return queries.details(id);
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
