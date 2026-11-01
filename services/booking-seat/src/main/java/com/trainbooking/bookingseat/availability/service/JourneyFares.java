package com.trainbooking.bookingseat.availability.service;

import com.trainbooking.bookingseat.availability.model.FareInfo;
import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.common.CoachClass;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The price of a journey: a ticket costs the sum of the fares of the segments it uses, for the class of the seat; a
 * standing place costs the fare of the class of its coach (CLAUDE.md, "Fares"). Amounts are always worked out on the
 * server from the fares of the trip, never taken from the client.
 */
public final class JourneyFares {

    private JourneyFares() {
    }

    /**
     * The price of the whole journey per class. A class is listed only if every segment of the journey has a fare for it,
     * so a class that cannot be priced completely is never offered at a wrong price.
     */
    public static Map<CoachClass, BigDecimal> perClass(Journey journey, List<FareInfo> fares) {
        Map<CoachClass, BigDecimal> prices = new EnumMap<>(CoachClass.class);
        for (CoachClass coachClass : CoachClass.values()) {
            BigDecimal total = BigDecimal.ZERO;
            boolean complete = true;
            for (int segment = journey.fromStop(); segment < journey.toStop(); segment++) {
                BigDecimal amount = amountOf(fares, segment, coachClass);
                if (amount == null) {
                    complete = false;
                    break;
                }
                total = total.add(amount);
            }
            if (complete) {
                prices.put(coachClass, total.setScale(2));
            }
        }
        return prices;
    }

    private static BigDecimal amountOf(List<FareInfo> fares, int segment, CoachClass coachClass) {
        for (FareInfo fare : fares) {
            if (fare.segmentIndex() == segment && fare.coachClass() == coachClass) {
                return fare.amount();
            }
        }
        return null;
    }
}
