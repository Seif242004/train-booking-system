package com.trainbooking.bookingseat.snapshot.controller;

import static com.trainbooking.bookingseat.testsupport.Snapshots.valid;
import static com.trainbooking.bookingseat.testsupport.Snapshots.withSeats;
import static com.trainbooking.bookingseat.testsupport.TestJwts.admin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.GlobalExceptionHandler;
import com.trainbooking.bookingseat.security.JsonErrorHandlers;
import com.trainbooking.bookingseat.security.SecurityConfig;
import com.trainbooking.bookingseat.snapshot.model.SnapshotConflictException;
import com.trainbooking.bookingseat.snapshot.model.SnapshotResult;
import com.trainbooking.bookingseat.snapshot.model.TripSnapshot;
import com.trainbooking.bookingseat.snapshot.service.SnapshotService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(value = SnapshotController.class, properties = "trainbooking.internal-api-key=test-key")
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class SnapshotControllerTest {

    private static final String KEY = "test-key";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @MockitoBean
    SnapshotService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private MockHttpServletRequestBuilder push(long id, Object body) {
        return put("/internal/trips/{id}/snapshot", id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    // ---- who may call it --------------------------------------------------------------------------------------------

    @Test
    void withoutTheKey_returns401_andNothingHappens() throws Exception {
        mvc.perform(push(21, valid()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
        verifyNoInteractions(service);
    }

    @Test
    void aWrongKey_returns401() throws Exception {
        mvc.perform(push(21, valid()).header("X-Internal-Api-Key", "test-kez"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
        verifyNoInteractions(service);
    }

    @Test
    void anAdminsToken_isNotEnough_theKeyIsRequired() throws Exception {
        mvc.perform(push(21, valid()).with(admin()))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/internal/trips/{id}", 21).with(admin()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    // ---- the request --------------------------------------------------------------------------------------------------

    @Test
    void aValidSnapshot_isApplied_andTheResultIsReturned() throws Exception {
        given(service.apply(eq(21L), any(TripSnapshot.class), anyString())).willReturn(new SnapshotResult(21, true, 4, 3, 4, 9, 6));

        mvc.perform(push(21, valid()).header("X-Internal-Api-Key", KEY).header("X-Acting-User", "admin-sub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(21))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.seats").value(4))
                .andExpect(jsonPath("$.standingCounters").value(6));
        verify(service).apply(eq(21L), any(TripSnapshot.class), eq("admin-sub"));
    }

    @Test
    void withoutAnActingUser_theCallerIsRecordedAsTrainAndSchedule() throws Exception {
        given(service.apply(eq(21L), any(TripSnapshot.class), anyString())).willReturn(new SnapshotResult(21, false, 4, 3, 4, 9, 6));

        mvc.perform(push(21, valid()).header("X-Internal-Api-Key", KEY)).andExpect(status().isOk());

        verify(service).apply(eq(21L), any(TripSnapshot.class), eq("train-schedule"));
    }

    @Test
    void theJsonNames_classRowAndColumn_areAccepted() throws Exception {
        String body = """
                {"departureDate":"2026-11-02","train":{"number":"T1","name":"N","type":"Express"},"routeName":"R",
                 "stops":[{"stopIndex":0,"stationId":1,"stationCode":"A","stationName":"A","departureTime":"2026-11-02T08:00:00+02:00"},
                          {"stopIndex":1,"stationId":2,"stationCode":"B","stationName":"B","arrivalTime":"2026-11-02T09:00:00+02:00"}],
                 "coaches":[{"id":1,"code":"A","class":"FIRST","position":1,"standingCapacity":2}],
                 "seats":[{"id":1,"coachId":1,"seatNumber":"A1","row":1,"column":"A","class":"FIRST"}],
                 "fares":[{"segmentIndex":0,"class":"FIRST","amount":80.00,"currency":"EGP"}]}
                """;
        given(service.apply(eq(5L), any(TripSnapshot.class), anyString())).willReturn(new SnapshotResult(5, true, 2, 1, 1, 1, 1));

        mvc.perform(put("/internal/trips/{id}/snapshot", 5).header("X-Internal-Api-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void aFieldThatIsMissing_returns400_namingIt_andNothingIsApplied() throws Exception {
        TripSnapshot noRoute = new TripSnapshot(valid().departureDate(), valid().train(), " ", valid().stops(), valid().coaches(), valid().seats(), valid().fares());

        mvc.perform(push(21, noRoute).header("X-Internal-Api-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[0].field").value("routeName"))
                .andExpect(jsonPath("$.error.details[0].message").value("is required"));
        verifyNoInteractions(service);
    }

    @Test
    void aSnapshotWhosePartsDoNotFitTogether_returns400_namingThePath() throws Exception {
        TripSnapshot orphanSeat = withSeats(valid(), List.of(new TripSnapshot.Seat(1L, 99L, "A1", 1, "A", com.trainbooking.bookingseat.common.CoachClass.FIRST)));

        mvc.perform(push(21, orphanSeat).header("X-Internal-Api-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[0].field").value("seats[0].coachId"));
        verifyNoInteractions(service);
    }

    @Test
    void aBodyThatIsNotJson_returns400() throws Exception {
        mvc.perform(put("/internal/trips/{id}/snapshot", 21).header("X-Internal-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("malformed_request"));
    }

    // ---- conflicts ------------------------------------------------------------------------------------------------------

    @Test
    void aSnapshotThatConflictsWithReservations_returns409_withItsCodeAndDetails() throws Exception {
        given(service.apply(eq(21L), any(TripSnapshot.class), anyString())).willThrow(new SnapshotConflictException("capacity_conflict",
                "The change would remove seats that have holds or bookings.", List.of(new ApiError.Detail("seats", "seat B1 has reservations"))));

        mvc.perform(push(21, valid()).header("X-Internal-Api-Key", KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("capacity_conflict"))
                .andExpect(jsonPath("$.error.details[0].message").value("seat B1 has reservations"));
    }

    // ---- delete -----------------------------------------------------------------------------------------------------------

    @Test
    void deletingATrip_returns204() throws Exception {
        mvc.perform(delete("/internal/trips/{id}", 21).header("X-Internal-Api-Key", KEY).header("X-Acting-User", "admin-sub"))
                .andExpect(status().isNoContent());
        verify(service).delete(21L, "admin-sub");
    }

    @Test
    void deletingATripThatHasReservations_returns409() throws Exception {
        doThrow(new SnapshotConflictException("trip_has_reservations", "The trip has holds or bookings.", List.of()))
                .when(service).delete(eq(21L), anyString());

        mvc.perform(delete("/internal/trips/{id}", 21).header("X-Internal-Api-Key", KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("trip_has_reservations"));
    }
}
