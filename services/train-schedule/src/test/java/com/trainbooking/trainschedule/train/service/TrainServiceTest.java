package com.trainbooking.trainschedule.train.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.model.TrainNotFoundException;
import com.trainbooking.trainschedule.train.model.TrainNumberTakenException;
import com.trainbooking.trainschedule.train.model.TrainRequest;
import com.trainbooking.trainschedule.train.model.TrainStatus;
import com.trainbooking.trainschedule.train.repository.TrainRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;

class TrainServiceTest {

    private final TrainRepository trains = mock(TrainRepository.class);
    private final AuditLogRepository auditLog = mock(AuditLogRepository.class);
    private final TrainService service = new TrainService(trains, new AuditTrail(auditLog, JsonMapper.builder().build()));

    private static final Train EXISTING = new Train(5, "E901", "Cairo Express", "Express", TrainStatus.ACTIVE);

    @Test
    void create_normalizesTheNumber_andAuditsTheAdmin() {
        given(trains.insert("E901", "Cairo Express", "Express", TrainStatus.ACTIVE)).willReturn(EXISTING);

        Train created = service.create(new TrainRequest(" e901 ", " Cairo Express ", " Express ", "ACTIVE"), "admin-1");

        assertThat(created.number()).isEqualTo("E901");
        verify(auditLog).record(eq("admin-1"), eq("train"), eq(5L), eq("CREATE"), anyString());
    }

    @Test
    void create_duplicateNumber_isReportedAsTaken_andNothingIsAudited() {
        given(trains.insert(anyString(), anyString(), anyString(), any())).willThrow(new DuplicateKeyException("ORA-00001"));

        assertThatThrownBy(() -> service.create(new TrainRequest("E901", "X", "Express", "ACTIVE"), "admin-1"))
                .isInstanceOf(TrainNumberTakenException.class);
        verify(auditLog, never()).record(any(), any(), anyLong(), any(), any());
    }

    @Test
    void update_recordsBeforeAndAfter_includingTheStatusChange() {
        given(trains.findById(5)).willReturn(Optional.of(EXISTING));
        given(trains.update(5, "E901", "Cairo Express", "Express", TrainStatus.INACTIVE)).willReturn(true);

        Train updated = service.update(5, new TrainRequest("E901", "Cairo Express", "Express", "INACTIVE"), "admin-1");

        assertThat(updated.status()).isEqualTo(TrainStatus.INACTIVE);
        verify(auditLog).record(eq("admin-1"), eq("train"), eq(5L), eq("UPDATE"), contains("INACTIVE"));
    }

    @Test
    void update_unknownTrain_isNotFound() {
        given(trains.findById(9)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(9, new TrainRequest("E1", "X", "Express", "ACTIVE"), "admin-1"))
                .isInstanceOf(TrainNotFoundException.class);
    }

    @Test
    void delete_recordsHowManyCoachesAndSeatsTheCascadeRemoved() {
        given(trains.findById(5)).willReturn(Optional.of(EXISTING));
        given(trains.countCoaches(5)).willReturn(2);
        given(trains.countSeats(5)).willReturn(80);
        given(trains.delete(5)).willReturn(true);

        service.delete(5, "admin-1");

        verify(auditLog).record(eq("admin-1"), eq("train"), eq(5L), eq("DELETE"), contains("\"cascadedSeats\":80"));
    }

    @Test
    void delete_aTrainThatTripsUse_isReportedAsInUse_andNothingIsAudited() {
        given(trains.findById(5)).willReturn(Optional.of(EXISTING));
        given(trains.delete(5)).willThrow(new org.springframework.dao.DataIntegrityViolationException(
                "ORA-02292: integrity constraint (TRAIN_SCHEDULE.FK_TRIP_TRAIN) violated - child record found"));

        assertThatThrownBy(() -> service.delete(5, "admin-1"))
                .isInstanceOf(com.trainbooking.trainschedule.train.model.TrainInUseException.class);
        verify(auditLog, never()).record(any(), any(), anyLong(), any(), any());
    }

    @Test
    void delete_anotherIntegrityProblem_isNotHiddenAsInUse() {
        given(trains.findById(5)).willReturn(Optional.of(EXISTING));
        given(trains.delete(5)).willThrow(new org.springframework.dao.DataIntegrityViolationException("something else"));

        assertThatThrownBy(() -> service.delete(5, "admin-1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void list_blankFiltersAreIgnored_andAnUnknownStatusIsRejected() {
        service.list(" ", "", null);
        verify(trains).search(null, null, null);

        assertThatThrownBy(() -> service.list(null, null, "SOMETIMES")).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void list_statusFilterIsCaseInsensitive() {
        service.list("e9", null, "inactive");

        verify(trains).search("e9", null, TrainStatus.INACTIVE);
    }
}
