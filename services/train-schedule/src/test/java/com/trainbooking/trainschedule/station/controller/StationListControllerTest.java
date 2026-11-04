package com.trainbooking.trainschedule.station.controller;

import static com.trainbooking.trainschedule.testsupport.TestJwts.passenger;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.trainschedule.common.GlobalExceptionHandler;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import com.trainbooking.trainschedule.station.model.Station;
import com.trainbooking.trainschedule.station.service.StationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer test of {@link StationListController}: the read-only /stations list: signed-in passengers may read it, no token
 * gives 401, and it offers no way to change stations. Only the controller, the security chain and the error handler are loaded;
 * the service is a mock.
 */
// @WebMvcTest(X.class): starts a slice of Spring with only the web layer (MockMvc, no real server, no database).
// @Import: adds the security configuration and the JSON error handlers, so 401/403 and error bodies are the real ones.
@WebMvcTest(StationListController.class)
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class StationListControllerTest {

    // @Autowired: Spring puts its MockMvc bean here; MockMvc sends fake HTTP requests straight to the controller.
    @Autowired
    MockMvc mvc;

    // @MockitoBean: replaces the real bean in the Spring context with a Mockito mock, so each test decides what the service returns or
    // throws.
    @MockitoBean
    StationService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    // @Test: marks a JUnit 5 test method. Names read as sentences: who calls, what happens.
    @Test
    void withoutAToken_returns401() throws Exception {
        mvc.perform(get("/stations")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void anOrdinaryPassenger_canReadTheStations() throws Exception {
        given(service.list()).willReturn(List.of(new Station(1, "CAI", "Cairo", "Cairo"), new Station(3, "ALX", "Alexandria", "Alexandria")));

        mvc.perform(get("/stations").with(passenger()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("CAI"))
                .andExpect(jsonPath("$[1].name").value("Alexandria"));
    }

    @Test
    void theListIsReadOnly_aPassengerCannotChangeStations() throws Exception {
        // POST and DELETE on /stations are not routes at all (405); changing stations is only under /admin/stations.
        mvc.perform(post("/stations").with(passenger())).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/stations").with(passenger())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
}
