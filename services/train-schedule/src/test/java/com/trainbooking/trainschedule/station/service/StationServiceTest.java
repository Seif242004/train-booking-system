package com.trainbooking.trainschedule.station.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.trainbooking.trainschedule.audit.AuditLogRepository;
import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.common.SnapshotChanges;
import com.trainbooking.trainschedule.station.model.Station;
import com.trainbooking.trainschedule.station.model.StationCodeTakenException;
import com.trainbooking.trainschedule.station.model.StationNotFoundException;
import com.trainbooking.trainschedule.station.model.StationRequest;
import com.trainbooking.trainschedule.station.repository.StationRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;

class StationServiceTest {

    private final StationRepository stations = mock(StationRepository.class);
    private final AuditLogRepository audit = mock(AuditLogRepository.class);
    private final SnapshotChanges changes = mock(SnapshotChanges.class);
    private final StationService service = new StationService(stations, new AuditTrail(audit, JsonMapper.builder().build()), changes);

    @Test
    void create_normalizesInput_andWritesAnAuditRowForTheAdmin() {
        given(stations.insert("CAI", "Cairo Ramses", "Cairo")).willReturn(new Station(5, "CAI", "Cairo Ramses", "Cairo"));

        Station created = service.create(new StationRequest(" cai ", " Cairo Ramses ", " Cairo "), "admin-1");

        assertThat(created.code()).isEqualTo("CAI");
        verify(audit).record(eq("admin-1"), eq("station"), eq(5L), eq("CREATE"), anyString());
    }

    @Test
    void create_duplicateCode_isReportedAsTaken_andNothingIsAudited() {
        given(stations.insert(anyString(), anyString(), anyString())).willThrow(new DuplicateKeyException("ORA-00001"));

        assertThatThrownBy(() -> service.create(new StationRequest("CAI", "Cairo Ramses", "Cairo"), "admin-1"))
                .isInstanceOf(StationCodeTakenException.class);
        verify(audit, never()).record(any(), any(), anyLong(), any(), any());
    }

    @Test
    void update_recordsBeforeAndAfter() {
        given(stations.findById(5)).willReturn(Optional.of(new Station(5, "CAI", "Cairo Ramses", "Cairo")));
        given(stations.update(5, "CAI", "Cairo Central", "Cairo")).willReturn(true);

        Station updated = service.update(5, new StationRequest("CAI", "Cairo Central", "Cairo"), "admin-1");

        assertThat(updated.name()).isEqualTo("Cairo Central");
        verify(audit).record(eq("admin-1"), eq("station"), eq(5L), eq("UPDATE"),
                org.mockito.ArgumentMatchers.contains("Cairo Ramses"));
        org.mockito.Mockito.verify(changes).stationChanged(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void update_unknownStation_isNotFound() {
        given(stations.findById(9)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(9, new StationRequest("CAI", "Cairo", "Cairo"), "admin-1"))
                .isInstanceOf(StationNotFoundException.class);
        org.mockito.Mockito.verifyNoInteractions(changes); // a refused or failed edit pushes nothing
    }

    @Test
    void delete_removesTheStation_andAuditsIt() {
        given(stations.findById(5)).willReturn(Optional.of(new Station(5, "CAI", "Cairo Ramses", "Cairo")));
        given(stations.delete(5)).willReturn(true);

        service.delete(5, "admin-1");

        verify(audit).record(eq("admin-1"), eq("station"), eq(5L), eq("DELETE"), anyString());
    }

    @Test
    void delete_aStationThatARouteUses_isReportedAsInUse_andNothingIsAudited() {
        given(stations.findById(5)).willReturn(Optional.of(new Station(5, "CAI", "Cairo Ramses", "Cairo")));
        given(stations.delete(5)).willThrow(new org.springframework.dao.DataIntegrityViolationException(
                "ORA-02292: integrity constraint (TRAIN_SCHEDULE.FK_ROUTE_STOP_STATION) violated - child record found"));

        assertThatThrownBy(() -> service.delete(5, "admin-1"))
                .isInstanceOf(com.trainbooking.trainschedule.station.model.StationInUseException.class);
        verify(audit, never()).record(any(), any(), anyLong(), any(), any());
    }

    @Test
    void delete_anotherIntegrityProblem_isNotHiddenAsInUse() {
        given(stations.findById(5)).willReturn(Optional.of(new Station(5, "CAI", "Cairo Ramses", "Cairo")));
        given(stations.delete(5)).willThrow(new org.springframework.dao.DataIntegrityViolationException("something else"));

        assertThatThrownBy(() -> service.delete(5, "admin-1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
