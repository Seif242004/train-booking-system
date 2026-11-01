package com.trainbooking.trainschedule.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.trainbooking.trainschedule.audit.AuditLogRepository;
import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.common.SnapshotChanges;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.fare.repository.FareRepository;
import com.trainbooking.trainschedule.route.model.Route;
import com.trainbooking.trainschedule.route.model.RouteHasFaresException;
import com.trainbooking.trainschedule.route.model.RouteInUseException;
import com.trainbooking.trainschedule.route.model.RouteNotFoundException;
import com.trainbooking.trainschedule.route.model.RouteRequest;
import com.trainbooking.trainschedule.route.model.RouteStop;
import com.trainbooking.trainschedule.route.repository.RouteRepository;
import com.trainbooking.trainschedule.station.service.StationService;
import com.trainbooking.trainschedule.trip.repository.TripRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RouteServiceTest {

    private final RouteRepository routes = mock(RouteRepository.class);
    private final FareRepository fares = mock(FareRepository.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final StationService stations = mock(StationService.class);
    private final AuditLogRepository auditLog = mock(AuditLogRepository.class);
    private final SnapshotChanges changes = mock(SnapshotChanges.class);
    private final RouteService service =
            new RouteService(routes, fares, trips, stations, new AuditTrail(auditLog, JsonMapper.builder().build()), changes);

    private static Route route(long id, String name, long... stationIds) {
        RouteStop[] stops = new RouteStop[stationIds.length];
        for (int i = 0; i < stationIds.length; i++) {
            stops[i] = new RouteStop(i, stationIds[i], "S" + stationIds[i], "Station " + stationIds[i], "City");
        }
        return new Route(id, name, List.of(stops));
    }

    @Test
    void create_savesTheRouteAndStopsInOrder_andAudits() {
        given(stations.existingIds(any())).willReturn(Set.of(1L, 2L, 3L));
        given(routes.insert("Cairo - Alexandria")).willReturn(4L);
        given(routes.findById(4)).willReturn(Optional.of(route(4, "Cairo - Alexandria", 1, 2, 3)));

        Route created = service.create(new RouteRequest(" Cairo - Alexandria ", List.of(1L, 2L, 3L)), "admin-1");

        assertThat(created.stops()).hasSize(3);
        verify(routes).insertStops(4, List.of(1L, 2L, 3L));
        verify(auditLog).record(eq("admin-1"), eq("route"), eq(4L), eq("CREATE"), anyString());
    }

    @Test
    void create_aStationVisitedTwice_isRejected_namingTheRepeatedStop() {
        given(stations.existingIds(any())).willReturn(Set.of(1L, 2L));

        assertThatThrownBy(() -> service.create(new RouteRequest("X", List.of(1L, 2L, 1L)), "admin-1"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).details())
                        .extracting(ApiError.Detail::field).containsExactly("stops[2]"));
        verify(routes, never()).insert(anyString());
    }

    @Test
    void create_anUnknownStation_isRejected_namingTheStop_andNothingIsSaved() {
        given(stations.existingIds(any())).willReturn(Set.of(1L, 3L));

        assertThatThrownBy(() -> service.create(new RouteRequest("X", List.of(1L, 99L, 3L)), "admin-1"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).details())
                        .extracting(ApiError.Detail::field, ApiError.Detail::message)
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("stops[1]", "station 99 does not exist")));
        verify(routes, never()).insert(anyString());
        verify(auditLog, never()).record(any(), any(), anyLong(), any(), any());
    }

    @Test
    void update_renamingOnly_leavesStopsAndFaresAlone() {
        given(routes.findById(4)).willReturn(Optional.of(route(4, "Old", 1, 2, 3)), Optional.of(route(4, "New", 1, 2, 3)));
        given(routes.updateName(4, "New")).willReturn(true);

        Route updated = service.update(4, new RouteRequest("New", List.of(1L, 2L, 3L)), false, "admin-1");

        assertThat(updated.name()).isEqualTo("New");
        verify(routes, never()).deleteStops(anyLong());
        verify(fares, never()).deleteByRoute(anyLong());
        verify(fares, never()).countByRoute(anyLong());
        org.mockito.Mockito.verify(changes).routeChanged(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void update_changingTheStopsOfARouteWithFares_isRefusedUntilConfirmed() {
        given(routes.findById(4)).willReturn(Optional.of(route(4, "R", 1, 2, 3)));
        given(stations.existingIds(any())).willReturn(Set.of(1L, 2L, 4L));
        given(fares.countByRoute(4)).willReturn(6);

        assertThatThrownBy(() -> service.update(4, new RouteRequest("R", List.of(1L, 2L, 4L)), false, "admin-1"))
                .isInstanceOf(RouteHasFaresException.class);
        verify(routes, never()).deleteStops(anyLong());
        verify(fares, never()).deleteByRoute(anyLong());
        verify(auditLog, never()).record(any(), any(), anyLong(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(changes); // a refused or failed edit pushes nothing
    }

    @Test
    void update_changingTheStopsWithClearFares_removesTheFares_andAuditsHowMany() {
        given(routes.findById(4)).willReturn(Optional.of(route(4, "R", 1, 2, 3)), Optional.of(route(4, "R", 1, 2, 4)));
        given(stations.existingIds(any())).willReturn(Set.of(1L, 2L, 4L));
        given(fares.countByRoute(4)).willReturn(6);
        given(fares.deleteByRoute(4)).willReturn(6);
        given(routes.updateName(4, "R")).willReturn(true);

        service.update(4, new RouteRequest("R", List.of(1L, 2L, 4L)), true, "admin-1");

        verify(fares).deleteByRoute(4);
        verify(routes).deleteStops(4);
        verify(routes).insertStops(4, List.of(1L, 2L, 4L));
        verify(auditLog).record(eq("admin-1"), eq("route"), eq(4L), eq("UPDATE"), contains("\"faresCleared\":6"));
    }

    @Test
    void update_changingTheStopsOfARouteWithoutFares_justWorks() {
        given(routes.findById(4)).willReturn(Optional.of(route(4, "R", 1, 2)), Optional.of(route(4, "R", 1, 3)));
        given(stations.existingIds(any())).willReturn(Set.of(1L, 3L));
        given(fares.countByRoute(4)).willReturn(0);
        given(routes.updateName(4, "R")).willReturn(true);

        service.update(4, new RouteRequest("R", List.of(1L, 3L)), false, "admin-1");

        verify(routes).insertStops(eq(4L), anyList());
        verify(fares, never()).deleteByRoute(anyLong());
    }

    @Test
    void update_changingTheStopsOfARouteThatTripsUse_isRefused_evenWithClearFares() {
        given(routes.findById(4)).willReturn(Optional.of(route(4, "R", 1, 2, 3)));
        given(trips.countByRoute(4)).willReturn(2);

        assertThatThrownBy(() -> service.update(4, new RouteRequest("R", List.of(1L, 2L, 4L)), true, "admin-1"))
                .isInstanceOf(RouteInUseException.class);
        verify(routes, never()).deleteStops(anyLong());
        verify(fares, never()).deleteByRoute(anyLong());
    }

    @Test
    void update_renamingARouteThatTripsUse_isFine() {
        given(routes.findById(4)).willReturn(Optional.of(route(4, "Old", 1, 2, 3)), Optional.of(route(4, "New", 1, 2, 3)));
        given(routes.updateName(4, "New")).willReturn(true);
        given(trips.countByRoute(4)).willReturn(2);

        assertThat(service.update(4, new RouteRequest("New", List.of(1L, 2L, 3L)), false, "admin-1").name()).isEqualTo("New");
    }

    @Test
    void delete_aRouteThatTripsUse_isReportedAsInUse_andNothingIsAudited() {
        given(routes.findById(4)).willReturn(Optional.of(route(4, "R", 1, 2, 3)));
        given(routes.delete(4)).willThrow(new org.springframework.dao.DataIntegrityViolationException(
                "ORA-02292: integrity constraint (TRAIN_SCHEDULE.FK_TRIP_ROUTE) violated - child record found"));

        assertThatThrownBy(() -> service.delete(4, "admin-1")).isInstanceOf(RouteInUseException.class);
        verify(auditLog, never()).record(any(), any(), anyLong(), any(), any());
    }

    @Test
    void update_unknownRoute_isNotFound() {
        given(routes.findById(9)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(9, new RouteRequest("R", List.of(1L, 2L)), false, "admin-1"))
                .isInstanceOf(RouteNotFoundException.class);
    }

    @Test
    void delete_recordsHowManyFaresTheCascadeRemoved() {
        given(routes.findById(4)).willReturn(Optional.of(route(4, "R", 1, 2, 3)));
        given(fares.countByRoute(4)).willReturn(6);
        given(routes.delete(4)).willReturn(true);

        service.delete(4, "admin-1");

        verify(auditLog).record(eq("admin-1"), eq("route"), eq(4L), eq("DELETE"), contains("\"cascadedFares\":6"));
    }
}
