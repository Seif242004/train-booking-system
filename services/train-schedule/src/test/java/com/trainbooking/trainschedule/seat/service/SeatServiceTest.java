package com.trainbooking.trainschedule.seat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
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
import com.trainbooking.trainschedule.seat.model.Seat;
import com.trainbooking.trainschedule.seat.model.SeatConflictException;
import com.trainbooking.trainschedule.seat.model.SeatRequest;
import com.trainbooking.trainschedule.seat.repository.SeatRepository;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;

class SeatServiceTest {

    private final SeatRepository seats = mock(SeatRepository.class);
    private final CoachService coaches = mock(CoachService.class);
    private final AuditLogRepository auditLog = mock(AuditLogRepository.class);
    private final SeatService service = new SeatService(seats, coaches,
            Validation.buildDefaultValidatorFactory().getValidator(), new AuditTrail(auditLog, JsonMapper.builder().build()));

    private static final Coach SECOND_CLASS = new Coach(5, 1, "B2", CoachClass.SECOND, 2, 0);

    private void coachExists() {
        given(coaches.get(5)).willReturn(SECOND_CLASS);
    }

    private static SeatRequest seat(String number, Integer row, String column) {
        return new SeatRequest(number, row, column);
    }

    private List<ApiError.Detail> detailsOf(Throwable thrown) {
        return thrown instanceof RequestValidationException v ? v.details() : ((SeatConflictException) thrown).details();
    }

    @Test
    void addSeats_savesAllWithTheCoachClass_normalizesThem_andWritesOneAuditRow() {
        coachExists();
        given(seats.findByCoach(5)).willReturn(List.of()).willReturn(List.of(
                new Seat(1, 5, "1A", 1, "A", CoachClass.SECOND), new Seat(2, 5, "1B", 1, "B", CoachClass.SECOND)));

        List<Seat> created = service.addSeats(5, List.of(seat("1a", 1, "a"), seat("1B", 1, "B")), "admin-1");

        assertThat(created).extracting(Seat::seatNumber).containsExactly("1A", "1B");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> inserted = ArgumentCaptor.forClass(List.class);
        verify(seats).insertAll(eq(5L), eq(CoachClass.SECOND), inserted.capture());
        assertThat(inserted.getValue()).extracting(Seat::coachClass).containsOnly(CoachClass.SECOND);
        assertThat(inserted.getValue()).extracting(Seat::column).containsExactly("A", "B");
        verify(auditLog).record(eq("admin-1"), eq("coach"), eq(5L), eq("ADD_SEATS"), contains("\"count\":2"));
    }

    @Test
    void addSeats_aRepeatedSeatNumberInTheList_rejectsTheWholeRequest() {
        coachExists();

        assertThatThrownBy(() -> service.addSeats(5, List.of(seat("1A", 1, "A"), seat("1a", 1, "B")), "admin-1"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(detailsOf(e)).extracting(ApiError.Detail::field).containsExactly("[1].seatNumber"));
        verify(seats, never()).insertAll(anyLong(), any(), any());
        verify(auditLog, never()).record(any(), any(), anyLong(), any(), any());
    }

    @Test
    void addSeats_aRepeatedRowAndColumnInTheList_rejectsTheWholeRequest() {
        coachExists();

        assertThatThrownBy(() -> service.addSeats(5, List.of(seat("1A", 1, "A"), seat("X9", 1, "a")), "admin-1"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(detailsOf(e)).extracting(ApiError.Detail::field).containsExactly("[1].column"));
        verify(seats, never()).insertAll(anyLong(), any(), any());
    }

    @Test
    void addSeats_anInvalidSeatInTheMiddle_namesItsPosition_andSavesNothing() {
        coachExists();

        assertThatThrownBy(() -> service.addSeats(5,
                List.of(seat("1A", 1, "A"), seat("", 0, "1"), seat("1C", 1, "C")), "admin-1"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(detailsOf(e)).extracting(ApiError.Detail::field)
                        .contains("[1].seatNumber", "[1].row", "[1].column")
                        .noneMatch(f -> f.startsWith("[0]") || f.startsWith("[2]")));
        verify(seats, never()).insertAll(anyLong(), any(), any());
    }

    @Test
    void addSeats_emptyNullElementAndTooLongLists_areRejected() {
        coachExists();
        List<SeatRequest> tooMany = new ArrayList<>();
        for (int i = 1; i <= SeatService.MAX_SEATS_PER_REQUEST + 1; i++) {
            tooMany.add(seat("S" + i, i % 99 + 1, "A"));
        }
        List<SeatRequest> withNull = new ArrayList<>();
        withNull.add(seat("1A", 1, "A"));
        withNull.add(null);

        assertThatThrownBy(() -> service.addSeats(5, List.of(), "a")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> service.addSeats(5, null, "a")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> service.addSeats(5, tooMany, "a")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> service.addSeats(5, withNull, "a"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(detailsOf(e)).extracting(ApiError.Detail::field).containsExactly("[1]"));
        verify(seats, never()).insertAll(anyLong(), any(), any());
    }

    @Test
    void addSeats_clashingWithExistingSeats_isAConflict_listingEachClash() {
        coachExists();
        given(seats.findByCoach(5)).willReturn(List.of(new Seat(1, 5, "1A", 1, "A", CoachClass.SECOND)));

        assertThatThrownBy(() -> service.addSeats(5, List.of(seat("2A", 2, "A"), seat("1A", 3, "C"), seat("9Z", 1, "A")), "admin-1"))
                .isInstanceOf(SeatConflictException.class)
                .satisfies(e -> assertThat(detailsOf(e)).extracting(ApiError.Detail::field)
                        .containsExactlyInAnyOrder("[1].seatNumber", "[2].column"));
        verify(seats, never()).insertAll(anyLong(), any(), any());
    }

    @Test
    void addSeats_aClashDetectedOnlyByTheDatabase_isAConflict_andNothingIsAudited() {
        coachExists();
        given(seats.findByCoach(5)).willReturn(List.of());
        doThrow(new DuplicateKeyException("ORA-00001")).when(seats).insertAll(anyLong(), any(), any());

        assertThatThrownBy(() -> service.addSeats(5, List.of(seat("1A", 1, "A")), "admin-1"))
                .isInstanceOf(SeatConflictException.class);
        verify(auditLog, never()).record(any(), any(), anyLong(), any(), any());
    }

    @Test
    void update_aSeatNumberOrPositionAlreadyTaken_isAConflictNamingTheField() {
        Seat before = new Seat(3, 5, "1A", 1, "A", CoachClass.SECOND);
        given(seats.findById(3)).willReturn(Optional.of(before));
        given(seats.update(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString()))
                .willThrow(new DuplicateKeyException("ORA-00001: unique constraint (TRAIN_SCHEDULE.UK_SEAT_NUMBER) violated"))
                .willThrow(new DuplicateKeyException("ORA-00001: unique constraint (TRAIN_SCHEDULE.UK_SEAT_POSITION) violated"));

        assertThatThrownBy(() -> service.update(3, seat("1B", 1, "A"), "admin-1"))
                .satisfies(e -> assertThat(detailsOf(e)).extracting(ApiError.Detail::field).containsExactly("seatNumber"));
        assertThatThrownBy(() -> service.update(3, seat("1A", 1, "B"), "admin-1"))
                .satisfies(e -> assertThat(detailsOf(e)).extracting(ApiError.Detail::field).containsExactly("column"));
    }

    @Test
    void update_keepsTheCoachClassOfTheSeat() {
        Seat before = new Seat(3, 5, "1A", 1, "A", CoachClass.SECOND);
        given(seats.findById(3)).willReturn(Optional.of(before));
        given(seats.update(3, "2A", 2, "A")).willReturn(true);

        Seat after = service.update(3, seat("2a", 2, "a"), "admin-1");

        assertThat(after.coachClass()).isEqualTo(CoachClass.SECOND);
        assertThat(after.seatNumber()).isEqualTo("2A");
    }
}
