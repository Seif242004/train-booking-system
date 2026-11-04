package com.trainbooking.bookingseat.availability.controller;

import com.trainbooking.bookingseat.availability.model.AvailabilityResponse;
import com.trainbooking.bookingseat.availability.service.AvailabilityService;
import com.trainbooking.bookingseat.booking.service.BookingQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.RequestValidationException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Availability for a journey (SRS 9.1). It is called by the Gateway, which composes the search results and the seat map
 * with it; the browser does not call it directly. Any signed-in user may read it.
 */
// @RestController = @Controller + @ResponseBody: each method answers an HTTP request and its return value is written as JSON.
// The controller holds no logic: it reads the parameters, calls the services and shapes the answer.
@RestController
public class AvailabilityController {

    private final AvailabilityService service;
    private final BookingQueryService bookings;

    public AvailabilityController(AvailabilityService service, BookingQueryService bookings) {
        this.service = service;
        this.bookings = bookings;
    }

    /**
     * {@code GET /trips/{id}/availability?from=1&to=3}: {@code from} and {@code to} are stop indexes of the trip (the
     * boarding stop and the destination stop). {@code view=summary} leaves out the per-seat states and the per-coach
     * standing places and returns only the totals per class, which is all a search result needs.
     */
    // @GetMapping: this method answers GET on this path. {id} is a path variable. In the Gateway this path has no public route; the Gateway
    // calls it itself while composing /trips (summary) and /trips/{id}/seat-map (full). Any signed-in user passes the security chain.
    @GetMapping("/trips/{id}/availability")
    // @AuthenticationPrincipal gives the verified JWT of the caller (its subject is the user id). @PathVariable binds {id}. @RequestParam
    // binds
    // ?from= and ?to= (required: a missing one gives 400 missing_parameter) and view (default "full"). Errors: 404 trip_not_found,
    // 400 validation_failed for stops that are not on the trip or a bad view.
    public AvailabilityResponse availability(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestParam int from, @RequestParam int to,
                                             @RequestParam(defaultValue = "full") String view) {
        boolean summaryOnly = switch (view) {
            case "full" -> false;
            case "summary" -> true;
            default -> throw new RequestValidationException(List.of(new ApiError.Detail("view", "must be full or summary")));
        };
        var availability = service.availability(id, from, to);
        // The full view also says what the caller holds on the trip, so the seat map shows those seats as selected, with the countdown.
        var myHold = summaryOnly ? null : bookings.activeHold(jwt.getSubject(), id).orElse(null);
        return AvailabilityResponse.of(availability, summaryOnly, myHold);
    }
}
