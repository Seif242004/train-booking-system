package com.trainbooking.trainschedule.trip.controller;

import static com.trainbooking.trainschedule.testsupport.TestJwts.ADMIN_SUB;
import static com.trainbooking.trainschedule.testsupport.TestJwts.admin;
import static com.trainbooking.trainschedule.testsupport.TestJwts.passenger;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.GlobalExceptionHandler;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import com.trainbooking.trainschedule.trip.model.RouteFaresIncompleteException;
import com.trainbooking.trainschedule.trip.model.TrainHasNoCoachesException;
import com.trainbooking.trainschedule.trip.model.TrainInactiveException;
import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripNotFoundException;
import com.trainbooking.trainschedule.trip.model.TripStatus;
import com.trainbooking.trainschedule.trip.model.TripStop;
import com.trainbooking.trainschedule.trip.service.TripService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TripController.class)
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class TripControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final String BODY = """
            {"trainId":7,"routeId":4,"departureDate":"2026-11-02","stops":[
              {"departureTime":"08:00"},{"arrivalTime":"08:40","departureTime":"08:45"},{"arrivalTime":"10:45"}]}""";
    private static final Trip TRIP = new Trip(11, 7, "T100", "Express", 4, "Cairo-Alexandria", DAY, TripStatus.ACTIVE, List.of(
            new TripStop(0, 1, "CAI", "Cairo", null, DAY.atTime(8, 0)),
            new TripStop(1, 2, "GIZ", "Giza", DAY.atTime(8, 40), DAY.atTime(8, 45)),
            new TripStop(2, 3, "ALX", "Alexandria", DAY.atTime(10, 45), null)));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TripService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void asPassenger_returns403_forEveryOperation() throws Exception {
        mvc.perform(get("/admin/trips").with(passenger())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/trips/11").with(passenger())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/trips").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(put("/admin/trips/11").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(delete("/admin/trips/11").with(passenger())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_listCanBeLimitedToADate_andShowsStopTimesAsLocalDateTimes() throws Exception {
        given(service.list(DAY)).willReturn(List.of(TRIP));

        mvc.perform(get("/admin/trips").param("date", "2026-11-02").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trainNumber").value("T100"))
                .andExpect(jsonPath("$[0].departureDate").value("2026-11-02"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].stops[0].arrivalTime").doesNotExist())
                .andExpect(jsonPath("$[0].stops[0].departureTime").value("2026-11-02T08:00:00"))
                .andExpect(jsonPath("$[0].stops[2].arrivalTime").value("2026-11-02T10:45:00"));
    }

    @Test
    void asAdmin_createReturns201_withRelativeLocation_andPassesTheAdminId() throws Exception {
        given(service.create(any(), eq(ADMIN_SUB))).willReturn(TRIP);

        mvc.perform(post("/admin/trips").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/admin/trips/11"))
                .andExpect(jsonPath("$.id").value(11));
    }

    @Test
    void asAdmin_missingFieldsAndBadTimeFormat_return400_withFieldDetails() throws Exception {
        mvc.perform(post("/admin/trips").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":4,\"stops\":[{\"departureTime\":\"8am\"},{\"arrivalTime\":\"10:45\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[?(@.field=='trainId')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='departureDate')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='stops[0].departureTime')]").exists());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_aDateThatIsNotADate_returns400() throws Exception {
        mvc.perform(post("/admin/trips").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trainId\":7,\"routeId\":4,\"departureDate\":\"tomorrow\",\"stops\":[{\"departureTime\":\"08:00\"},{\"arrivalTime\":\"10:00\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("malformed_request"));
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_timeProblemsFromTheService_return400_namingTheStop() throws Exception {
        given(service.create(any(), any())).willThrow(new RequestValidationException(
                List.of(new ApiError.Detail("stops[1].arrivalTime", "must be after the previous stop's departure"))));

        mvc.perform(post("/admin/trips").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("stops[1].arrivalTime"));
    }

    @Test
    void asAdmin_theCreateRules_return409_withTheirOwnCodes() throws Exception {
        given(service.create(any(), any()))
                .willThrow(new RouteFaresIncompleteException(List.of(new ApiError.Detail("fares", "segment 1 (Giza to Alexandria) has no SECOND fare"))))
                .willThrow(new TrainInactiveException("T100"))
                .willThrow(new TrainHasNoCoachesException("T100"));

        mvc.perform(post("/admin/trips").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("route_fares_incomplete"))
                .andExpect(jsonPath("$.error.details[0].message").value("segment 1 (Giza to Alexandria) has no SECOND fare"));
        mvc.perform(post("/admin/trips").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("train_inactive"));
        mvc.perform(post("/admin/trips").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("train_has_no_coaches"));
    }

    @Test
    void asAdmin_unknownTrip_returns404_updateAndDeleteWork() throws Exception {
        given(service.get(99)).willThrow(new TripNotFoundException(99));
        given(service.update(eq(11L), any(), eq(ADMIN_SUB))).willReturn(TRIP);

        mvc.perform(get("/admin/trips/99").with(admin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("trip_not_found"));
        mvc.perform(put("/admin/trips/11").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        mvc.perform(delete("/admin/trips/11").with(admin())).andExpect(status().isNoContent());
        verify(service).delete(11, ADMIN_SUB);
    }
}
