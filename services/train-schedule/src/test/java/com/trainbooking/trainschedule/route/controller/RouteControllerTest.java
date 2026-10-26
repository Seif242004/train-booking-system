package com.trainbooking.trainschedule.route.controller;

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
import com.trainbooking.trainschedule.route.model.Route;
import com.trainbooking.trainschedule.route.model.RouteHasFaresException;
import com.trainbooking.trainschedule.route.model.RouteNotFoundException;
import com.trainbooking.trainschedule.route.model.RouteStop;
import com.trainbooking.trainschedule.route.service.RouteService;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RouteController.class)
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class RouteControllerTest {

    private static final String BODY = "{\"name\":\"Cairo - Alexandria\",\"stops\":[1,2,3]}";
    private static final Route ROUTE = new Route(4, "Cairo - Alexandria", List.of(
            new RouteStop(0, 1, "CAI", "Cairo Ramses", "Cairo"),
            new RouteStop(1, 2, "TNT", "Tanta", "Tanta"),
            new RouteStop(2, 3, "ALX", "Alexandria", "Alexandria")));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RouteService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void asPassenger_returns403_forEveryOperation() throws Exception {
        mvc.perform(get("/admin/routes").with(passenger())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/routes/4").with(passenger())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/routes").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/admin/routes/4").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/admin/routes/4").with(passenger())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_listShowsStopsInOrderWithStationDetails() throws Exception {
        given(service.list()).willReturn(List.of(ROUTE));

        mvc.perform(get("/admin/routes").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Cairo - Alexandria"))
                .andExpect(jsonPath("$[0].stops[0].stopIndex").value(0))
                .andExpect(jsonPath("$[0].stops[0].stationCode").value("CAI"))
                .andExpect(jsonPath("$[0].stops[2].stationName").value("Alexandria"));
    }

    @Test
    void asAdmin_createReturns201_withRelativeLocation_andPassesTheAdminId() throws Exception {
        given(service.create(any(), eq(ADMIN_SUB))).willReturn(ROUTE);

        mvc.perform(post("/admin/routes").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/admin/routes/4"))
                .andExpect(jsonPath("$.stops.length()").value(3));
    }

    @Test
    void asAdmin_invalidBody_returns400_withFieldDetails() throws Exception {
        mvc.perform(post("/admin/routes").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"stops\":[1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[?(@.field=='name')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='stops')]").exists());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_aNullOrNonPositiveStopId_returns400() throws Exception {
        mvc.perform(post("/admin/routes").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"stops\":[1,null,-3]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_repeatedOrUnknownStations_returns400_namingTheStop() throws Exception {
        given(service.create(any(), any())).willThrow(new RequestValidationException(
                List.of(new ApiError.Detail("stops[2]", "station 1 is already stop 0; a route visits a station once"))));

        mvc.perform(post("/admin/routes").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("stops[2]"));
    }

    @Test
    void asAdmin_updatePassesClearFares_andDefaultsToFalse() throws Exception {
        given(service.update(eq(4L), any(), eq(false), eq(ADMIN_SUB))).willThrow(new RouteHasFaresException(4, 6));
        given(service.update(eq(4L), any(), eq(true), eq(ADMIN_SUB))).willReturn(ROUTE);

        mvc.perform(put("/admin/routes/4").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("route_has_fares"));
        mvc.perform(put("/admin/routes/4").param("clearFares", "true").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        verify(service).update(eq(4L), any(), eq(true), eq(ADMIN_SUB));
    }

    @Test
    void asAdmin_unknownRoute_returns404_andDeleteReturns204() throws Exception {
        given(service.get(99)).willThrow(new RouteNotFoundException(99));

        mvc.perform(get("/admin/routes/99").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("route_not_found"));
        mvc.perform(delete("/admin/routes/4").with(admin())).andExpect(status().isNoContent());
        verify(service).delete(4, ADMIN_SUB);
    }
}
