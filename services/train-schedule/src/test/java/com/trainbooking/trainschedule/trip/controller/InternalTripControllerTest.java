package com.trainbooking.trainschedule.trip.controller;

import static com.trainbooking.trainschedule.testsupport.TestJwts.admin;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.trainschedule.common.GlobalExceptionHandler;
import com.trainbooking.trainschedule.security.InternalSecurityConfig;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripNotFoundException;
import com.trainbooking.trainschedule.trip.model.TripStatus;
import com.trainbooking.trainschedule.trip.service.TripService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The entry for Booking and Seat: only the shared API key opens it, a user's token (even an admin's) does not. */
@WebMvcTest(value = InternalTripController.class, properties = "trainbooking.internal-api-key=test-key")
@Import({SecurityConfig.class, InternalSecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class InternalTripControllerTest {

    private static final Trip CANCELLED = new Trip(11, 7, "T100", "Express", 4, "Cairo-Alexandria", LocalDate.of(2026, 11, 2), TripStatus.CANCELLED, List.of());

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TripService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void withoutTheKey_orWithAWrongOne_or_aUsersToken_is401_andNothingHappens() throws Exception {
        mvc.perform(post("/internal/trips/11/cancel")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("invalid_api_key"));
        mvc.perform(post("/internal/trips/11/cancel").header("X-Internal-Api-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/trips/11/cancel").with(admin())).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void withTheKey_marksTheTripCancelled_forTheAdminNamed() throws Exception {
        given(service.markCancelled(11, "admin-sub")).willReturn(CANCELLED);

        mvc.perform(post("/internal/trips/11/cancel").header("X-Internal-Api-Key", "test-key").header("X-Acting-User", "admin-sub"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(11)).andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(service).markCancelled(11, "admin-sub");
    }

    @Test
    void withoutAnActingUser_theCallerIsBookingAndSeat() throws Exception {
        given(service.markCancelled(11, "booking-seat")).willReturn(CANCELLED);

        mvc.perform(post("/internal/trips/11/cancel").header("X-Internal-Api-Key", "test-key")).andExpect(status().isOk());
    }

    @Test
    void anUnknownTrip_is404() throws Exception {
        given(service.markCancelled(99, "booking-seat")).willThrow(new TripNotFoundException(99));

        mvc.perform(post("/internal/trips/99/cancel").header("X-Internal-Api-Key", "test-key")).andExpect(status().isNotFound());
    }
}
