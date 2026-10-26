package com.trainbooking.trainschedule.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.trainbooking.trainschedule.audit.AuditLogRepository;
import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.coach.service.CoachService;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.fare.model.Fare;
import com.trainbooking.trainschedule.fare.service.FareService;
import com.trainbooking.trainschedule.route.model.Route;
import com.trainbooking.trainschedule.route.model.RouteNotFoundException;
import com.trainbooking.trainschedule.route.model.RouteStop;
import com.trainbooking.trainschedule.route.service.RouteService;
import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.model.TrainNotFoundException;
import com.trainbooking.trainschedule.train.model.TrainStatus;
import com.trainbooking.trainschedule.train.service.TrainService;
import com.trainbooking.trainschedule.trip.model.RouteFaresIncompleteException;
import com.trainbooking.trainschedule.trip.model.StopTimeRequest;
import com.trainbooking.trainschedule.trip.model.TrainHasNoCoachesException;
import com.trainbooking.trainschedule.trip.model.TrainInactiveException;
import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripNotFoundException;
import com.trainbooking.trainschedule.trip.model.TripRequest;
import com.trainbooking.trainschedule.trip.model.TripStatus;
import com.trainbooking.trainschedule.trip.model.TripStop;
import com.trainbooking.trainschedule.trip.repository.TripRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class TripServiceTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    private final TripRepository trips = mock(TripRepository.class);
    private final TrainService trains = mock(TrainService.class);
    private final CoachService coaches = mock(CoachService.class);
    private final RouteService routes = mock(RouteService.class);
    private final FareService fares = mock(FareService.class);
    private final TripSnapshotPublisher snapshots = mock(TripSnapshotPublisher.class);
    private final AuditLogRepository auditLog = mock(AuditLogRepository.class);
    private final TripService service = new TripService(trips, trains, coaches, routes, fares, snapshots,
            new AuditTrail(auditLog, JsonMapper.builder().build()), CAIRO);

    private static final Train ACTIVE_TRAIN = new Train(7, "T100", "Express", "Express", TrainStatus.ACTIVE);
    private static final Train INACTIVE_TRAIN = new Train(7, "T100", "Express", "Express", TrainStatus.INACTIVE);
    private static final Route ROUTE = new Route(4, "Cairo-Alexandria", List.of(
            new RouteStop(0, 1, "CAI", "Cairo", "Cairo"),
            new RouteStop(1, 2, "GIZ", "Giza", "Giza"),
            new RouteStop(2, 3, "ALX", "Alexandria", "Alexandria")));
    private static final List<Coach> FIRST_AND_SECOND = List.of(
            new Coach(1, 7, "A1", CoachClass.FIRST, 1, 10), new Coach(2, 7, "B2", CoachClass.SECOND, 2, 30));

    private static List<Fare> fares(CoachClass... classes) {
        java.util.ArrayList<Fare> list = new java.util.ArrayList<>();
        for (int segment = 0; segment < 2; segment++) {
            for (CoachClass c : classes) {
                list.add(new Fare(segment, c, new BigDecimal("10.00"), "EGP"));
            }
        }
        return list;
    }

    private static TripRequest request(long trainId, long routeId) {
        return new TripRequest(trainId, routeId, DAY, List.of(
                new StopTimeRequest(null, "08:00"), new StopTimeRequest("08:40", "08:45"), new StopTimeRequest("10:45", null)));
    }

    private static Trip savedTrip(long id) {
        return new Trip(id, 7, "T100", "Express", 4, "Cairo-Alexandria", DAY, TripStatus.ACTIVE, List.of(
                new TripStop(0, 1, "CAI", "Cairo", null, DAY.atTime(8, 0))));
    }

    private void everythingValid() {
        given(trains.get(7)).willReturn(ACTIVE_TRAIN);
        given(routes.get(4)).willReturn(ROUTE);
        given(coaches.listByTrain(7)).willReturn(FIRST_AND_SECOND);
        given(fares.list(4)).willReturn(fares(CoachClass.FIRST, CoachClass.SECOND));
    }

    @Test
    void create_savesTheTripWithTimesInTheServiceZone_audits_andHandsOverTheSnapshot() {
        everythingValid();
        given(trips.insert(7, 4, DAY)).willReturn(11L);
        given(trips.findById(11)).willReturn(Optional.of(savedTrip(11)));

        Trip created = service.create(request(7, 4), "admin-1");

        assertThat(created.id()).isEqualTo(11);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TripRepository.StopRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(trips).insertStops(eq(11L), rows.capture());
        assertThat(rows.getValue()).extracting(TripRepository.StopRow::stationId).containsExactly(1L, 2L, 3L);
        assertThat(rows.getValue().get(0).arrival()).isNull();
        assertThat(rows.getValue().get(0).departure().toLocalDateTime()).isEqualTo(DAY.atTime(8, 0));
        assertThat(rows.getValue().get(0).departure().getOffset()).isEqualTo(CAIRO.getRules().getOffset(DAY.atTime(8, 0)));
        assertThat(rows.getValue().get(2).departure()).isNull();
        verify(auditLog).record(eq("admin-1"), eq("trip"), eq(11L), eq("CREATE"), anyString());
        verify(snapshots).publish(created);
    }

    @Test
    void create_anUnknownTrainOrRoute_isReportedOnTheFieldThatNamedIt() {
        given(trains.get(99)).willThrow(new TrainNotFoundException(99));
        assertThatThrownBy(() -> service.create(request(99, 4), "admin-1"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).details()).extracting(ApiError.Detail::field).containsExactly("trainId"));

        given(trains.get(7)).willReturn(ACTIVE_TRAIN);
        given(routes.get(99)).willThrow(new RouteNotFoundException(99));
        assertThatThrownBy(() -> service.create(request(7, 99), "admin-1"))
                .satisfies(e -> assertThat(((RequestValidationException) e).details()).extracting(ApiError.Detail::field).containsExactly("routeId"));
        verify(trips, never()).insert(anyLong(), anyLong(), any());
    }

    @Test
    void create_forAnInactiveTrain_isRefused() {
        given(trains.get(7)).willReturn(INACTIVE_TRAIN);
        given(routes.get(4)).willReturn(ROUTE);

        assertThatThrownBy(() -> service.create(request(7, 4), "admin-1")).isInstanceOf(TrainInactiveException.class);
        verify(trips, never()).insert(anyLong(), anyLong(), any());
    }

    @Test
    void create_forATrainWithoutCoaches_isRefused() {
        given(trains.get(7)).willReturn(ACTIVE_TRAIN);
        given(routes.get(4)).willReturn(ROUTE);
        given(coaches.listByTrain(7)).willReturn(List.of());

        assertThatThrownBy(() -> service.create(request(7, 4), "admin-1")).isInstanceOf(TrainHasNoCoachesException.class);
    }

    @Test
    void create_withTheWrongNumberOfStopTimes_isRejected() {
        everythingValid();
        TripRequest twoStopsOnAThreeStopRoute = new TripRequest(7L, 4L, DAY,
                List.of(new StopTimeRequest(null, "08:00"), new StopTimeRequest("10:45", null)));

        assertThatThrownBy(() -> service.create(twoStopsOnAThreeStopRoute, "admin-1"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).details()).extracting(ApiError.Detail::field).containsExactly("stops"));
    }

    @Test
    void create_whenTheRouteLacksFaresForAClassOfTheTrain_isRefused_listingEveryMissingFare() {
        everythingValid();
        // The train has FIRST and SECOND coaches; the route only has FIRST fares.
        given(fares.list(4)).willReturn(fares(CoachClass.FIRST));

        assertThatThrownBy(() -> service.create(request(7, 4), "admin-1"))
                .isInstanceOf(RouteFaresIncompleteException.class)
                .satisfies(e -> assertThat(((RouteFaresIncompleteException) e).details()).extracting(ApiError.Detail::message)
                        .containsExactly("segment 0 (Cairo to Giza) has no SECOND fare", "segment 1 (Giza to Alexandria) has no SECOND fare"));
        verify(trips, never()).insert(anyLong(), anyLong(), any());
        verify(snapshots, never()).publish(any());
    }

    @Test
    void create_aClassTheTrainDoesNotHaveNeedsNoFare() {
        everythingValid();
        given(coaches.listByTrain(7)).willReturn(List.of(new Coach(1, 7, "A1", CoachClass.FIRST, 1, 0)));
        given(fares.list(4)).willReturn(fares(CoachClass.FIRST)); // no SECOND or THIRD fares, and none are needed
        given(trips.insert(7, 4, DAY)).willReturn(11L);
        given(trips.findById(11)).willReturn(Optional.of(savedTrip(11)));

        assertThat(service.create(request(7, 4), "admin-1").id()).isEqualTo(11);
    }

    @Test
    void create_aMissingFareInOneSegmentOnly_isReportedForThatSegment() {
        everythingValid();
        given(fares.list(4)).willReturn(List.of(
                new Fare(0, CoachClass.FIRST, BigDecimal.TEN, "EGP"), new Fare(0, CoachClass.SECOND, BigDecimal.TEN, "EGP"),
                new Fare(1, CoachClass.FIRST, BigDecimal.TEN, "EGP")));

        assertThatThrownBy(() -> service.create(request(7, 4), "admin-1"))
                .satisfies(e -> assertThat(((RouteFaresIncompleteException) e).details()).extracting(ApiError.Detail::message)
                        .containsExactly("segment 1 (Giza to Alexandria) has no SECOND fare"));
    }

    @Test
    void update_keepingTheSameTrain_stillWorksForAnInactiveTrain_soTimesCanBeCorrected() {
        given(trips.findById(11)).willReturn(Optional.of(savedTrip(11)), Optional.of(savedTrip(11)));
        given(trains.get(7)).willReturn(INACTIVE_TRAIN);
        given(routes.get(4)).willReturn(ROUTE);
        given(coaches.listByTrain(7)).willReturn(FIRST_AND_SECOND);
        given(fares.list(4)).willReturn(fares(CoachClass.FIRST, CoachClass.SECOND));
        given(trips.update(11, 7, 4, DAY)).willReturn(true);

        service.update(11, request(7, 4), "admin-1");

        verify(trips).deleteStops(11);
        verify(trips).insertStops(eq(11L), anyList());
        verify(auditLog).record(eq("admin-1"), eq("trip"), eq(11L), eq("UPDATE"), anyString());
        verify(snapshots).publish(any());
    }

    @Test
    void update_movingTheTripToAnInactiveTrain_isRefused() {
        given(trips.findById(11)).willReturn(Optional.of(savedTrip(11)));
        given(trains.get(8)).willReturn(new Train(8, "T200", "Other", "Express", TrainStatus.INACTIVE));
        given(routes.get(4)).willReturn(ROUTE);

        assertThatThrownBy(() -> service.update(11, request(8, 4), "admin-1")).isInstanceOf(TrainInactiveException.class);
        verify(trips, never()).deleteStops(anyLong());
    }

    @Test
    void update_andDelete_ofAnUnknownTrip_areNotFound() {
        given(trips.findById(99)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99, request(7, 4), "admin-1")).isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> service.delete(99, "admin-1")).isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void delete_removesTheTrip_andAuditsIt() {
        given(trips.findById(11)).willReturn(Optional.of(savedTrip(11)));
        given(trips.delete(11)).willReturn(true);

        service.delete(11, "admin-1");

        verify(auditLog).record(eq("admin-1"), eq("trip"), eq(11L), eq("DELETE"), anyString());
    }
}
