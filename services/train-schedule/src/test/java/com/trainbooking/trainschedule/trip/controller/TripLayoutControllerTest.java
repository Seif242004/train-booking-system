package com.trainbooking.trainschedule.trip.controller;

import static com.trainbooking.trainschedule.testsupport.TestJwts.passenger;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.common.GlobalExceptionHandler;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import com.trainbooking.trainschedule.trip.model.TripLayout;
import com.trainbooking.trainschedule.trip.model.TripNotFoundException;
import com.trainbooking.trainschedule.trip.model.TripStatus;
import com.trainbooking.trainschedule.trip.service.TripLayoutService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TripLayoutController.class)
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class TripLayoutControllerTest {

    private static final TripLayout LAYOUT = new TripLayout(11, TripStatus.ACTIVE, LocalDate.of(2026, 11, 2), "T100", "Express", "Express",
            "Cairo-Alexandria", List.of(), List.of(new TripLayout.LayoutCoach(1, "A", CoachClass.FIRST, 1, 5,
            List.of(new TripLayout.LayoutSeat(100, "A1", 1, "A", CoachClass.FIRST)))));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TripLayoutService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void withoutAToken_returns401() throws Exception {
        mvc.perform(get("/trips/11/layout")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void anOrdinaryPassenger_canRead_theLayoutWithTheJsonClassName() throws Exception {
        given(service.layout(11)).willReturn(LAYOUT);

        mvc.perform(get("/trips/11/layout").with(passenger()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(11))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.coaches[0].class").value("FIRST"))
                .andExpect(jsonPath("$.coaches[0].standingCapacity").value(5))
                .andExpect(jsonPath("$.coaches[0].seats[0].seatNumber").value("A1"))
                .andExpect(jsonPath("$.coaches[0].seats[0].class").value("FIRST"));
    }

    @Test
    void anUnknownTrip_returns404() throws Exception {
        given(service.layout(99)).willThrow(new TripNotFoundException(99));

        mvc.perform(get("/trips/99/layout").with(passenger()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("trip_not_found"));
    }
}
