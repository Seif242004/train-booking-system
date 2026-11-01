package com.trainbooking.trainschedule.coach.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.coach.model.CoachCodeTakenException;
import com.trainbooking.trainschedule.coach.model.CoachPositionTakenException;
import com.trainbooking.trainschedule.coach.model.CoachRequest;
import com.trainbooking.trainschedule.coach.repository.CoachRepository;
import com.trainbooking.trainschedule.seat.repository.SeatRepository;
import com.trainbooking.trainschedule.train.model.TrainNotFoundException;
import com.trainbooking.trainschedule.train.service.TrainService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;

class CoachServiceTest {

    private final CoachRepository coaches = mock(CoachRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final TrainService trains = mock(TrainService.class);
    private final AuditLogRepository auditLog = mock(AuditLogRepository.class);
    private final SnapshotChanges changes = mock(SnapshotChanges.class);
    private final CoachService service =
            new CoachService(coaches, seats, trains, new AuditTrail(auditLog, JsonMapper.builder().build()), changes);

    private static final Coach FIRST_CLASS = new Coach(9, 4, "A1", CoachClass.FIRST, 1, 20);

    @Test
    void create_checksTheTrainExists_normalizesTheCode_andAudits() {
        given(coaches.insert(4, "A1", CoachClass.FIRST, 1, 20)).willReturn(FIRST_CLASS);

        Coach created = service.create(4, new CoachRequest(" a1 ", "FIRST", 1, 20), "admin-1");

        assertThat(created.code()).isEqualTo("A1");
        verify(trains).get(4);
        verify(auditLog).record(eq("admin-1"), eq("coach"), eq(9L), eq("CREATE"), anyString());
        org.mockito.Mockito.verify(changes).trainChanged(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void create_unknownTrain_isNotFound_andNothingIsSaved() {
        given(trains.get(99)).willThrow(new TrainNotFoundException(99));

        assertThatThrownBy(() -> service.create(99, new CoachRequest("A1", "FIRST", 1, 0), "admin-1"))
                .isInstanceOf(TrainNotFoundException.class);
        verify(coaches, never()).insert(anyLong(), anyString(), any(), anyInt(), anyInt());
        org.mockito.Mockito.verifyNoInteractions(changes); // a refused or failed edit pushes nothing
    }

    @Test
    void create_duplicatePosition_andDuplicateCode_areToldApartByTheViolatedConstraint() {
        given(coaches.insert(anyLong(), anyString(), any(), anyInt(), anyInt()))
                .willThrow(new DuplicateKeyException("ORA-00001: unique constraint (TRAIN_SCHEDULE.UK_COACH_POSITION) violated"))
                .willThrow(new DuplicateKeyException("ORA-00001: unique constraint (TRAIN_SCHEDULE.UK_COACH_CODE) violated"));

        assertThatThrownBy(() -> service.create(4, new CoachRequest("A1", "FIRST", 1, 0), "admin-1"))
                .isInstanceOf(CoachPositionTakenException.class);
        assertThatThrownBy(() -> service.create(4, new CoachRequest("A1", "FIRST", 2, 0), "admin-1"))
                .isInstanceOf(CoachCodeTakenException.class);
    }

    @Test
    void update_changingTheClass_updatesAllItsSeats_inTheSameCall() {
        given(coaches.findById(9)).willReturn(Optional.of(FIRST_CLASS));
        given(coaches.update(9, "A1", CoachClass.SECOND, 1, 20)).willReturn(true);
        given(seats.updateClassByCoach(9, CoachClass.SECOND)).willReturn(40);

        Coach updated = service.update(9, new CoachRequest("A1", "SECOND", 1, 20), "admin-1");

        assertThat(updated.coachClass()).isEqualTo(CoachClass.SECOND);
        verify(seats).updateClassByCoach(9, CoachClass.SECOND);
        verify(auditLog).record(eq("admin-1"), eq("coach"), eq(9L), eq("UPDATE"), contains("\"seatsReclassified\":40"));
        org.mockito.Mockito.verify(changes).trainChanged(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void update_withoutAClassChange_leavesTheSeatsAlone() {
        given(coaches.findById(9)).willReturn(Optional.of(FIRST_CLASS));
        given(coaches.update(9, "A1", CoachClass.FIRST, 1, 35)).willReturn(true);

        service.update(9, new CoachRequest("A1", "FIRST", 1, 35), "admin-1");

        verify(seats, never()).updateClassByCoach(anyLong(), any());
    }

    @Test
    void delete_recordsHowManySeatsTheCascadeRemoved() {
        given(coaches.findById(9)).willReturn(Optional.of(FIRST_CLASS));
        given(seats.countByCoach(9)).willReturn(40);
        given(coaches.delete(9)).willReturn(true);

        service.delete(9, "admin-1");

        verify(auditLog).record(eq("admin-1"), eq("coach"), eq(9L), eq("DELETE"), contains("\"cascadedSeats\":40"));
        org.mockito.Mockito.verify(changes).trainChanged(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }
}
