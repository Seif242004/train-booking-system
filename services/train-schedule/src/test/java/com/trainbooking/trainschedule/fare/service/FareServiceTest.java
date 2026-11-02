package com.trainbooking.trainschedule.fare.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.trainbooking.trainschedule.audit.AuditLogRepository;
import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.common.SnapshotChanges;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.fare.model.Fare;
import com.trainbooking.trainschedule.fare.model.FareRequest;
import com.trainbooking.trainschedule.fare.repository.FareRepository;
import com.trainbooking.trainschedule.route.model.Route;
import com.trainbooking.trainschedule.route.model.RouteNotFoundException;
import com.trainbooking.trainschedule.route.model.RouteStop;
import com.trainbooking.trainschedule.route.service.RouteService;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class FareServiceTest {

    private final FareRepository fares = mock(FareRepository.class);
    private final RouteService routes = mock(RouteService.class);
    private final AuditLogRepository auditLog = mock(AuditLogRepository.class);
    private final SnapshotChanges changes = mock(SnapshotChanges.class);
    private final FareService service = new FareService(fares, routes,
            Validation.buildDefaultValidatorFactory().getValidator(), new AuditTrail(auditLog, JsonMapper.builder().build()), changes);

    /** A route with three stops, so two segments: 0 and 1. */
    private static final Route THREE_STOPS = new Route(4, "R", List.of(
            new RouteStop(0, 1, "A", "A", "A"), new RouteStop(1, 2, "B", "B", "B"), new RouteStop(2, 3, "C", "C", "C")));

    private static FareRequest fare(Integer segment, String coachClass, String amount) {
        return new FareRequest(segment, coachClass, amount == null ? null : new BigDecimal(amount));
    }

    private static List<ApiError.Detail> detailsOf(Throwable thrown) {
        return ((RequestValidationException) thrown).details();
    }

    @Test
    void replaceAll_savesTheWholeSetInEgp_withTwoDecimals_andAuditsBeforeAndAfter() {
        given(routes.get(4)).willReturn(THREE_STOPS);
        given(fares.findByRoute(4)).willReturn(List.of()).willReturn(List.of(new Fare(0, CoachClass.FIRST, new BigDecimal("100.50"), "EGP")));

        List<Fare> saved = service.replaceAll(4, List.of(fare(0, "FIRST", "100.5"), fare(1, "THIRD", "40")), "admin-1");

        assertThat(saved).hasSize(1); // what the repository returns after the insert
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Fare>> inserted = ArgumentCaptor.forClass(List.class);
        verify(fares).deleteByRoute(4);
        verify(fares).insertAll(eq(4L), inserted.capture());
        assertThat(inserted.getValue()).extracting(Fare::currency).containsOnly("EGP");
        assertThat(inserted.getValue()).extracting(f -> f.amount().toPlainString()).containsExactly("100.50", "40.00");
        verify(auditLog).record(eq("admin-1"), eq("route"), eq(4L), eq("SET_FARES"), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(changes).routeChanged(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void replaceAll_aSegmentThatDoesNotExistOnTheRoute_isRejected() {
        given(routes.get(4)).willReturn(THREE_STOPS);

        assertThatThrownBy(() -> service.replaceAll(4, List.of(fare(0, "FIRST", "10"), fare(2, "FIRST", "10")), "admin-1"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(detailsOf(e)).extracting(ApiError.Detail::field).containsExactly("[1].segmentIndex"));
        verify(fares, never()).deleteByRoute(anyLong());
        org.mockito.Mockito.verifyNoInteractions(changes); // a refused or failed edit pushes nothing
    }

    @Test
    void replaceAll_theSameSegmentAndClassTwice_isRejected() {
        given(routes.get(4)).willReturn(THREE_STOPS);

        assertThatThrownBy(() -> service.replaceAll(4, List.of(fare(1, "SECOND", "10"), fare(1, "SECOND", "12")), "admin-1"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(detailsOf(e)).extracting(ApiError.Detail::field).containsExactly("[1].class"));
        verify(fares, never()).insertAll(anyLong(), anyList());
    }

    @Test
    void replaceAll_badAmountsAndClasses_areAllReported_andTheExistingFaresAreLeftAlone() {
        given(routes.get(4)).willReturn(THREE_STOPS);

        assertThatThrownBy(() -> service.replaceAll(4, List.of(
                fare(0, "FIRST", "0"),           // not more than 0
                fare(0, "SECOND", "-5"),         // negative
                fare(0, "THIRD", "10.999"),      // three decimals
                fare(1, "FOURTH", "10"),         // unknown class
                fare(1, "FIRST", null)), "admin-1")) // missing amount
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(detailsOf(e)).extracting(ApiError.Detail::field)
                        .containsExactlyInAnyOrder("[0].amount", "[1].amount", "[2].amount", "[3].class", "[4].amount"));
        verify(fares, never()).deleteByRoute(anyLong());
        verify(fares, never()).insertAll(anyLong(), anyList());
    }

    @Test
    void replaceAll_missingAmountReportsOnlyIsRequired() {
        given(routes.get(4)).willReturn(THREE_STOPS);

        assertThatThrownBy(() -> service.replaceAll(4, List.of(fare(0, "FIRST", null)), "admin-1"))
                .satisfies(e -> assertThat(detailsOf(e)).containsExactly(new ApiError.Detail("[0].amount", "is required")));
    }

    @Test
    void replaceAll_emptyNullOrTooLongLists_areRejected() {
        given(routes.get(4)).willReturn(THREE_STOPS);
        List<FareRequest> tooMany = new ArrayList<>();
        for (int i = 0; i <= FareService.MAX_FARES_PER_REQUEST; i++) {
            tooMany.add(fare(0, "FIRST", "1"));
        }

        assertThatThrownBy(() -> service.replaceAll(4, List.of(), "a")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> service.replaceAll(4, null, "a")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> service.replaceAll(4, tooMany, "a")).isInstanceOf(RequestValidationException.class);
        verify(fares, never()).deleteByRoute(anyLong());
    }

    @Test
    void replaceAll_unknownRoute_isNotFound() {
        given(routes.get(9)).willThrow(new RouteNotFoundException(9));

        assertThatThrownBy(() -> service.replaceAll(9, List.of(fare(0, "FIRST", "10")), "admin-1"))
                .isInstanceOf(RouteNotFoundException.class);
        verify(fares, never()).deleteByRoute(any(Long.class));
    }

    @Test
    void list_checksTheRouteExists() {
        given(routes.get(9)).willThrow(new RouteNotFoundException(9));

        assertThatThrownBy(() -> service.list(9)).isInstanceOf(RouteNotFoundException.class);
    }
}
