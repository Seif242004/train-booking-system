package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.availability.model.FareInfo;
import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.availability.service.JourneyFares;
import com.trainbooking.bookingseat.common.CoachClass;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The price of what a booking holds (CLAUDE.md, "Fares"): a seat costs the journey fare of its class (the sum of the fares of
 * the segments), a standing place costs the journey fare of the class of its coach. It is plain Java, worked out from the fares
 * of the copy of the trip; the amount of a booking never comes from the client.
 */
public final class BookingPricing {

    /** A seat to price: its id, its coach and its class. */
    public record Seat(long seatId, long coachId, CoachClass coachClass) {
    }

    /** Standing places to price: how many, in which coach, of which class. */
    public record Standing(long coachId, CoachClass coachClass, int qty) {
    }

    /** One priced line: a seat, or standing places in a coach. {@code price} is the price of the whole line. */
    public record Line(String type, Long seatId, long coachId, int qty, BigDecimal price) {
    }

    private BookingPricing() {
    }

    public static List<Line> price(Journey journey, List<FareInfo> fares, List<Seat> seats, List<Standing> standing) {
        Map<CoachClass, BigDecimal> perClass = JourneyFares.perClass(journey, fares);
        List<Line> lines = new ArrayList<>();
        for (Seat seat : seats) {
            lines.add(new Line("SEAT", seat.seatId(), seat.coachId(), 1, fareOf(perClass, seat.coachClass())));
        }
        for (Standing places : standing) {
            lines.add(new Line("STANDING", null, places.coachId(), places.qty(),
                    fareOf(perClass, places.coachClass()).multiply(BigDecimal.valueOf(places.qty()))));
        }
        return List.copyOf(lines);
    }

    public static BigDecimal total(List<Line> lines) {
        return lines.stream().map(Line::price).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }

    private static BigDecimal fareOf(Map<CoachClass, BigDecimal> perClass, CoachClass coachClass) {
        BigDecimal fare = perClass.get(coachClass);
        if (fare == null) {
            // The snapshot is only accepted with a fare for every segment and class, so this is a fault, not a user error.
            throw new IllegalStateException("The trip has no complete fare for the " + coachClass + " class");
        }
        return fare;
    }
}
