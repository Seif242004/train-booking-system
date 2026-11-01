package com.trainbooking.bookingseat.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.bookingseat.availability.model.FareInfo;
import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.booking.service.BookingPricing.Line;
import com.trainbooking.bookingseat.booking.service.BookingPricing.Seat;
import com.trainbooking.bookingseat.booking.service.BookingPricing.Standing;
import com.trainbooking.bookingseat.common.CoachClass;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The amount of a booking is worked out on the server from the fares of the trip, never from anything the client sends. */
class BookingPricingTest {

    private static FareInfo fare(int segment, CoachClass coachClass, String amount) {
        return new FareInfo(segment, coachClass, new BigDecimal(amount));
    }

    private static final List<FareInfo> FARES = List.of(
            fare(0, CoachClass.FIRST, "80"), fare(1, CoachClass.FIRST, "150"),
            fare(0, CoachClass.SECOND, "50"), fare(1, CoachClass.SECOND, "100"));

    @Test
    void aSeatCostsTheSumOfTheSegmentsOfItsClass() {
        List<Line> lines = BookingPricing.price(new Journey(0, 2), FARES, List.of(new Seat(1, 10, CoachClass.FIRST), new Seat(2, 20, CoachClass.SECOND)), List.of());

        assertThat(lines).extracting(Line::seatId, Line::price).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, new BigDecimal("230.00")), org.assertj.core.groups.Tuple.tuple(2L, new BigDecimal("150.00")));
        assertThat(BookingPricing.total(lines)).isEqualByComparingTo("380.00");
    }

    @Test
    void aJourneyOfOneSegmentCostsThatSegmentOnly() {
        List<Line> lines = BookingPricing.price(new Journey(1, 2), FARES, List.of(new Seat(1, 10, CoachClass.FIRST)), List.of());

        assertThat(lines.get(0).price()).isEqualByComparingTo("150.00");
    }

    @Test
    void standingPlacesCostTheClassFareOfTheCoach_perPlace() {
        List<Line> lines = BookingPricing.price(new Journey(0, 2), FARES, List.of(), List.of(new Standing(20, CoachClass.SECOND, 3)));

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.type()).isEqualTo("STANDING");
            assertThat(line.qty()).isEqualTo(3);
            assertThat(line.price()).isEqualByComparingTo("450.00"); // 3 x (50 + 100)
        });
    }

    @Test
    void seatsAndStandingTogetherAddUp() {
        List<Line> lines = BookingPricing.price(new Journey(0, 2), FARES, List.of(new Seat(1, 10, CoachClass.FIRST)), List.of(new Standing(20, CoachClass.SECOND, 2)));

        assertThat(BookingPricing.total(lines)).isEqualByComparingTo("530.00"); // 230 + 2 x 150
        assertThat(lines).extracting(Line::type).containsExactly("SEAT", "STANDING");
    }

    @Test
    void aClassThatCannotBePricedCompletely_isRefusedNeverPricedWrongly() {
        List<FareInfo> missingSegment = List.of(fare(0, CoachClass.FIRST, "80"));

        assertThatThrownBy(() -> BookingPricing.price(new Journey(0, 2), missingSegment, List.of(new Seat(1, 10, CoachClass.FIRST)), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FIRST");
    }

    @Test
    void theTotalOfNothingIsZero() {
        assertThat(BookingPricing.total(List.of())).isEqualByComparingTo("0.00");
    }
}
