package com.trainbooking.trainschedule.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.coach.service.CoachService;
import com.trainbooking.trainschedule.seat.model.Seat;
import com.trainbooking.trainschedule.seat.service.SeatService;
import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.model.TrainStatus;
import com.trainbooking.trainschedule.train.service.TrainService;
import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripLayout;
import com.trainbooking.trainschedule.trip.model.TripNotFoundException;
import com.trainbooking.trainschedule.trip.model.TripStatus;
import com.trainbooking.trainschedule.trip.model.TripStop;
import com.trainbooking.trainschedule.trip.repository.TripRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TripLayoutServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private final TripRepository trips = mock(TripRepository.class);
    private final TrainService trains = mock(TrainService.class);
    private final CoachService coaches = mock(CoachService.class);
    private final SeatService seats = mock(SeatService.class);
    private final TripLayoutService service = new TripLayoutService(trips, trains, coaches, seats);

    @Test
    void layout_hasTheTrainTheStopsAndEveryCoachWithItsSeatsAndStanding() {
        given(trips.findById(11)).willReturn(Optional.of(new Trip(11, 7, "T100", "Express", 4, "Cairo-Alexandria", DAY, TripStatus.ACTIVE,
                List.of(new TripStop(0, 1, "CAI", "Cairo", null, DAY.atTime(8, 0)), new TripStop(1, 2, "ALX", "Alexandria", DAY.atTime(10, 45), null)))));
        given(trains.get(7)).willReturn(new Train(7, "T100", "Cairo-Alexandria Express", "Express", TrainStatus.ACTIVE));
        given(coaches.listByTrain(7)).willReturn(List.of(new Coach(1, 7, "A", CoachClass.FIRST, 1, 5), new Coach(2, 7, "B", CoachClass.SECOND, 2, 0)));
        given(seats.listByCoach(1)).willReturn(List.of(new Seat(100, 1, "A1", 1, "A", CoachClass.FIRST), new Seat(101, 1, "A2", 1, "B", CoachClass.FIRST)));
        given(seats.listByCoach(2)).willReturn(List.of(new Seat(200, 2, "B1", 1, "A", CoachClass.SECOND)));

        TripLayout layout = service.layout(11);

        assertThat(layout.tripId()).isEqualTo(11);
        assertThat(layout.status()).isEqualTo(TripStatus.ACTIVE);
        assertThat(layout.trainName()).isEqualTo("Cairo-Alexandria Express");
        assertThat(layout.stops()).hasSize(2);
        assertThat(layout.coaches()).extracting(TripLayout.LayoutCoach::code, TripLayout.LayoutCoach::standingCapacity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("A", 5), org.assertj.core.groups.Tuple.tuple("B", 0));
        assertThat(layout.coaches().get(0).seats()).extracting(TripLayout.LayoutSeat::seatNumber).containsExactly("A1", "A2");
        assertThat(layout.coaches().get(1).seats()).extracting(TripLayout.LayoutSeat::id).containsExactly(200L);
    }

    @Test
    void layout_ofAnUnknownTrip_isNotFound() {
        given(trips.findById(99)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.layout(99)).isInstanceOf(TripNotFoundException.class);
    }
}
