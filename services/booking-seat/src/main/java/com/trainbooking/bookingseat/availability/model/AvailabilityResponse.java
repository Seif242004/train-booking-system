package com.trainbooking.bookingseat.availability.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.trainbooking.bookingseat.common.CoachClass;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The JSON answer of {@code GET /trips/{id}/availability}. {@code seats} and {@code standing} are left out of the
 * summary view, which is what the search results use. {@code fares} is the price of the whole journey per class in EGP
 * (the sum of its segments); the seat map shows it, and a hold uses the same numbers to work out the amount.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AvailabilityResponse(
        long tripId,
        String tripStatus,
        int fromStop,
        int toStop,
        Summary summary,
        Map<CoachClass, BigDecimal> fares,
        Long version,
        com.trainbooking.bookingseat.booking.model.BookingView myHold,
        List<Seat> seats,
        List<CoachStandingLeft> standing) {

    /** Seats and standing places left for the journey, per class of coach. */
    public record Summary(Map<CoachClass, Integer> seatsLeft, Map<CoachClass, Integer> standingLeft) {
    }

    public record Seat(long seatId, long coachId, SeatState state) {
    }

    public static AvailabilityResponse of(TripAvailability trip, boolean summaryOnly, com.trainbooking.bookingseat.booking.model.BookingView myHold) {
        JourneyAvailability a = trip.availability();
        Summary summary = new Summary(a.seatsLeft(), a.standingLeft());
        if (summaryOnly) {
            return new AvailabilityResponse(trip.tripId(), trip.tripStatus(), a.journey().fromStop(), a.journey().toStop(), summary, null, null, null, null, null);
        }
        List<Seat> seats = a.seats().stream().map(s -> new Seat(s.seatId(), s.coachId(), s.state())).toList();
        return new AvailabilityResponse(trip.tripId(), trip.tripStatus(), a.journey().fromStop(), a.journey().toStop(), summary, trip.fares(), trip.version(), myHold, seats, a.standing());
    }
}
