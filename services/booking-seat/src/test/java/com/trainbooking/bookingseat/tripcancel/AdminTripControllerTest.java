package com.trainbooking.bookingseat.tripcancel;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.common.GlobalExceptionHandler;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import com.trainbooking.bookingseat.security.JsonErrorHandlers;
import com.trainbooking.bookingseat.security.SecurityConfig;
import com.trainbooking.bookingseat.tripcancel.controller.AdminTripController;
import com.trainbooking.bookingseat.tripcancel.model.TripCancellationStatus;
import com.trainbooking.bookingseat.tripcancel.service.TripCancellationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Whole-trip cancellation: only the ADMIN role, a reason is required, and the answer says where it stands. */
@WebMvcTest(value = AdminTripController.class, properties = "trainbooking.internal-api-key=test-key")
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class AdminTripControllerTest {

    private static final TripCancellationStatus PENDING = new TripCancellationStatus(2, "CANCELLING", "Strike", "ADMIN-sub", false, 1, 2, 3, 0);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TripCancellationService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String role) {
        return jwt().jwt(t -> t.subject(role + "-sub")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cancel(String body) {
        return post("/admin/trips/2/cancel").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void withoutAToken_is401_andAPassengerIs403_andNothingHappens() throws Exception {
        mvc.perform(cancel("{\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(cancel("{\"reason\":\"x\"}").with(as("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/trips/2/cancellation").with(as("USER"))).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void anAdmin_cancelsTheTrip_withTheReason_andSeesWhereItStands() throws Exception {
        given(service.request(eq("ADMIN-sub"), eq(2L), eq("Strike"))).willReturn(PENDING);

        mvc.perform(cancel("{\"reason\":\" Strike \"}").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLING"))
                .andExpect(jsonPath("$.pendingHolds").value(1))
                .andExpect(jsonPath("$.pendingConfirmed").value(2))
                .andExpect(jsonPath("$.cancelledBookings").value(3))
                .andExpect(jsonPath("$.trainScheduleNotified").value(false));

        verify(service).request("ADMIN-sub", 2L, "Strike");
    }

    @Test
    void aReasonIsRequired() throws Exception {
        mvc.perform(cancel("{}").with(as("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(cancel("{\"reason\":\"  \"}").with(as("ADMIN"))).andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void refusalsAreExplained() throws Exception {
        given(service.request(eq("ADMIN-sub"), eq(2L), eq("x"))).willThrow(new BookingConflictException("trip_departed", "left"));
        given(service.request(eq("ADMIN-sub"), eq(3L), eq("x"))).willThrow(new TripNotFoundException(3));

        mvc.perform(cancel("{\"reason\":\"x\"}").with(as("ADMIN"))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("trip_departed"));
        mvc.perform(post("/admin/trips/3/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}").with(as("ADMIN")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("trip_not_found"));
    }

    @Test
    void theStatus_isReadableByAnAdmin() throws Exception {
        given(service.status(2)).willReturn(PENDING);

        mvc.perform(get("/admin/trips/2/cancellation").with(as("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("Strike"));
    }
}
