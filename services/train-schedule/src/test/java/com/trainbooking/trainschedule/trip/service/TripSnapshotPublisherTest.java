package com.trainbooking.trainschedule.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.coach.service.CoachService;
import com.trainbooking.trainschedule.fare.model.Fare;
import com.trainbooking.trainschedule.fare.service.FareService;
import com.trainbooking.trainschedule.seat.model.Seat;
import com.trainbooking.trainschedule.seat.service.SeatService;
import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.model.TrainStatus;
import com.trainbooking.trainschedule.train.service.TrainService;
import com.trainbooking.trainschedule.trip.model.BookingSnapshot;
import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripStatus;
import com.trainbooking.trainschedule.trip.model.TripStop;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TripSnapshotPublisherTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    private final TrainService trains = mock(TrainService.class);
    private final CoachService coaches = mock(CoachService.class);
    private final SeatService seats = mock(SeatService.class);
    private final FareService fares = mock(FareService.class);
    private final BookingSeatClient booking = mock(BookingSeatClient.class);
    private final TripSnapshotPublisher publisher = new TripSnapshotPublisher(trains, coaches, seats, fares, booking, CAIRO);

    private static final Trip TRIP = new Trip(11, 7, "T100", "Cairo-Alexandria Express", 4, "Cairo-Alexandria", DAY, TripStatus.ACTIVE, List.of(
            new TripStop(0, 1, "CAI", "Cairo", null, DAY.atTime(8, 0)),
            new TripStop(1, 2, "GIZ", "Giza", DAY.atTime(8, 40), DAY.atTime(8, 45)),
            new TripStop(2, 3, "ALX", "Alexandria", DAY.plusDays(1).atTime(0, 15), null)));

    private void trainWithTwoCoaches() {
        given(trains.get(7)).willReturn(new Train(7, "T100", "Cairo-Alexandria Express", "Express", TrainStatus.ACTIVE));
        given(coaches.listByTrain(7)).willReturn(List.of(new Coach(1, 7, "A", CoachClass.FIRST, 1, 0), new Coach(2, 7, "B", CoachClass.SECOND, 2, 12)));
        given(seats.listByCoach(1)).willReturn(List.of(new Seat(100, 1, "A1", 1, "A", CoachClass.FIRST)));
        given(seats.listByCoach(2)).willReturn(List.of(new Seat(200, 2, "B1", 1, "A", CoachClass.SECOND), new Seat(201, 2, "B2", 1, "B", CoachClass.SECOND)));
        given(fares.list(4)).willReturn(List.of(new Fare(0, CoachClass.FIRST, new BigDecimal("80.00"), "EGP"), new Fare(0, CoachClass.SECOND, new BigDecimal("50.00"), "EGP")));
    }

    @Test
    void theSnapshotHasEverythingBookingNeeds_andNothingFromAnotherTrain() {
        trainWithTwoCoaches();

        BookingSnapshot snapshot = publisher.build(TRIP);

        assertThat(snapshot.departureDate()).isEqualTo(DAY);
        assertThat(snapshot.train()).isEqualTo(new BookingSnapshot.Train("T100", "Cairo-Alexandria Express", "Express"));
        assertThat(snapshot.routeName()).isEqualTo("Cairo-Alexandria");
        assertThat(snapshot.stops()).extracting(BookingSnapshot.Stop::stationCode).containsExactly("CAI", "GIZ", "ALX");
        assertThat(snapshot.coaches()).extracting(BookingSnapshot.Coach::code, BookingSnapshot.Coach::standingCapacity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("A", 0), org.assertj.core.groups.Tuple.tuple("B", 12));
        assertThat(snapshot.seats()).extracting(BookingSnapshot.Seat::seatNumber, BookingSnapshot.Seat::coachId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("A1", 1L), org.assertj.core.groups.Tuple.tuple("B1", 2L), org.assertj.core.groups.Tuple.tuple("B2", 2L));
        assertThat(snapshot.fares()).extracting(f -> f.coachClass() + ":" + f.amount().toPlainString()).containsExactly("FIRST:80.00", "SECOND:50.00");
    }

    @Test
    void timesGoWithTheirOffset_soTheyMeanTheSameMomentOnBothSides() {
        trainWithTwoCoaches();

        BookingSnapshot snapshot = publisher.build(TRIP);

        BookingSnapshot.Stop first = snapshot.stops().get(0);
        assertThat(first.arrivalTime()).isNull();
        assertThat(first.departureTime().toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 11, 2, 8, 0));
        assertThat(first.departureTime().getOffset()).isEqualTo(CAIRO.getRules().getOffset(first.departureTime().toLocalDateTime()));
        // The last stop is after midnight: the next day, with the offset of that day.
        BookingSnapshot.Stop last = snapshot.stops().get(2);
        assertThat(last.arrivalTime().toLocalDate()).isEqualTo(DAY.plusDays(1));
        assertThat(last.departureTime()).isNull();
    }

    @Test
    void publish_pushesTheSnapshotOfTheTrip_forTheAdmin() {
        trainWithTwoCoaches();

        publisher.publish(TRIP, "admin-sub");

        ArgumentCaptor<BookingSnapshot> pushed = ArgumentCaptor.forClass(BookingSnapshot.class);
        verify(booking).putSnapshot(org.mockito.ArgumentMatchers.eq(11L), pushed.capture(), org.mockito.ArgumentMatchers.eq("admin-sub"));
        assertThat(pushed.getValue().seats()).hasSize(3);
    }

    @Test
    void remove_asksBookingToDropTheCopy() {
        publisher.remove(11, "admin-sub");

        verify(booking).deleteTrip(11, "admin-sub");
    }
}
