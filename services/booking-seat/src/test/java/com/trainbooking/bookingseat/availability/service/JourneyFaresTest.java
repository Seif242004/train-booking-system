package com.trainbooking.bookingseat.availability.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.bookingseat.availability.model.FareInfo;
import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.common.CoachClass;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit test of the pure pricing class JourneyFares: a journey costs the sum of the fares of its segments, per class. No database. */
class JourneyFaresTest {

    // Builds one fare row (segment, class, amount) for the tests.
    private static FareInfo fare(int segment, CoachClass coachClass, String amount) {
        return new FareInfo(segment, coachClass, new BigDecimal(amount));
    }

    private static final List<FareInfo> FARES = List.of(
            fare(0, CoachClass.FIRST, "80"), fare(1, CoachClass.FIRST, "150"), fare(2, CoachClass.FIRST, "40.50"),
            fare(0, CoachClass.SECOND, "50"), fare(1, CoachClass.SECOND, "100"), fare(2, CoachClass.SECOND, "25"));

    // @Test: JUnit 5: this method is one test. It passes when it ends without an exception; its name states the rule it proves.
    @Test
    void aJourneyCostsTheSumOfTheSegmentsItUses() {
        assertThat(JourneyFares.perClass(new Journey(0, 3), FARES)).containsEntry(CoachClass.FIRST, new BigDecimal("270.50"))
                .containsEntry(CoachClass.SECOND, new BigDecimal("175.00"));
        assertThat(JourneyFares.perClass(new Journey(1, 3), FARES)).containsEntry(CoachClass.FIRST, new BigDecimal("190.50"));
    }

    @Test
    void aJourneyOfOneSegmentCostsThatSegmentsFareOnly() {
        assertThat(JourneyFares.perClass(new Journey(1, 2), FARES)).containsEntry(CoachClass.FIRST, new BigDecimal("150.00"))
                .containsEntry(CoachClass.SECOND, new BigDecimal("100.00"));
    }

    @Test
    void aClassWithAMissingSegmentFareIsLeftOut_notPricedWrongly() {
        List<FareInfo> fares = List.of(fare(0, CoachClass.FIRST, "80"), fare(1, CoachClass.FIRST, "150"), fare(0, CoachClass.SECOND, "50"));

        assertThat(JourneyFares.perClass(new Journey(0, 2), fares)).containsOnlyKeys(CoachClass.FIRST);
        assertThat(JourneyFares.perClass(new Journey(0, 1), fares)).containsOnlyKeys(CoachClass.FIRST, CoachClass.SECOND);
    }

    @Test
    void aClassWithoutAnyFareIsNotListed() {
        assertThat(JourneyFares.perClass(new Journey(0, 3), FARES)).doesNotContainKey(CoachClass.THIRD);
    }
}
