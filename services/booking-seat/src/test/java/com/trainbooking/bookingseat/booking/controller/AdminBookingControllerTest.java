package com.trainbooking.bookingseat.booking.controller;

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
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.service.AdminBookingQueryService;
import com.trainbooking.bookingseat.booking.model.AdminBookingView;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import com.trainbooking.bookingseat.common.GlobalExceptionHandler;
import com.trainbooking.bookingseat.security.JsonErrorHandlers;
import com.trainbooking.bookingseat.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Support cancellation: only the ADMIN role, a reason is required, and it is the same cancellation as the passenger's. */
@WebMvcTest(value = AdminBookingController.class, properties = "trainbooking.internal-api-key=test-key")
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class AdminBookingControllerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-01T10:00:00Z");
    private static final BookingView CANCELLED = new BookingView(31, "TB-ABC234", BookingStatus.CANCELLED, RefundStatus.REFUND_PENDING, 2, 0, 2,
            new BigDecimal("230.00"), "EGP", NOW.plusMinutes(5), NOW, NOW, null, List.of());

    @Autowired
    MockMvc mvc;

    @MockitoBean
    com.trainbooking.bookingseat.booking.service.BookingIssueService issues;

    @MockitoBean
    CancellationService cancellations;

    @MockitoBean
    AdminBookingQueryService queries;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String role) {
        return jwt().jwt(t -> t.subject(role + "-sub")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cancel(String body) {
        return post("/admin/bookings/31/cancel").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void withoutAToken_is401_andAPassengerIs403_andNothingIsCancelled() throws Exception {
        mvc.perform(cancel("{\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(cancel("{\"reason\":\"x\"}").with(as("USER"))).andExpect(status().isForbidden());

        verifyNoInteractions(cancellations);
    }

    @Test
    void anAdmin_cancelsWithAReason_recordedAsTheActor() throws Exception {
        given(cancellations.cancel(eq(CancellationService.Actor.admin("ADMIN-sub")), eq(31L), eq("Called support"))).willReturn(CANCELLED);

        mvc.perform(cancel("{\"reason\":\"  Called support  \"}").with(as("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.refundStatus").value("REFUND_PENDING"));

        verify(cancellations).cancel(CancellationService.Actor.admin("ADMIN-sub"), 31L, "Called support");
    }

    private static final AdminBookingView DETAILS = new AdminBookingView(CANCELLED, "alice-sub", "alice@example.com", "Alice", "PAID", NOW, NOW, "admin-sub", "ADMIN", "Called support", false);

    @Test
    void searchAndDetails_areForAdminsOnly() throws Exception {
        mvc.perform(get("/admin/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/bookings").with(as("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/bookings/31").with(as("USER"))).andExpect(status().isForbidden());

        verifyNoInteractions(queries);
    }

    @Test
    void search_passesTheFiltersOn_andReturnsTheList() throws Exception {
        given(queries.search("TB-ABC234", "alice", 2L, "CONFIRMED", "REFUND_FAILED", null)).willReturn(List.of(DETAILS));

        mvc.perform(get("/admin/bookings").param("ref", "TB-ABC234").param("email", "alice").param("tripId", "2").param("status", "CONFIRMED")
                        .param("refundStatus", "REFUND_FAILED").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].booking.reference").value("TB-ABC234"))
                .andExpect(jsonPath("$[0].userEmail").value("alice@example.com"))
                .andExpect(jsonPath("$[0].paymentStatus").value("PAID"))
                .andExpect(jsonPath("$[0].cancelledBy").value("admin-sub"));
    }

    @Test
    void details_ofABooking_andAnUnknownOneIs404() throws Exception {
        given(queries.details(31)).willReturn(DETAILS);
        given(queries.details(32)).willThrow(new BookingNotFoundException(32));

        mvc.perform(get("/admin/bookings/31").with(as("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.cancelReason").value("Called support"));
        mvc.perform(get("/admin/bookings/32").with(as("ADMIN"))).andExpect(status().isNotFound());
    }

    @Test
    void aReasonIsRequired() throws Exception {
        mvc.perform(cancel("{}").with(as("ADMIN"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("reason"));
        mvc.perform(cancel("{\"reason\":\"   \"}").with(as("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(cancel("{\"reason\":\"" + "x".repeat(501) + "\"}").with(as("ADMIN"))).andExpect(status().isBadRequest());

        verifyNoInteractions(cancellations);
    }

    @Test
    void refusalsAreExplained() throws Exception {
        given(cancellations.cancel(eq(CancellationService.Actor.admin("ADMIN-sub")), eq(31L), eq("x"))).willThrow(new BookingConflictException("trip_departed", "left"));
        given(cancellations.cancel(eq(CancellationService.Actor.admin("ADMIN-sub")), eq(32L), eq("x"))).willThrow(new BookingNotFoundException(32));

        mvc.perform(cancel("{\"reason\":\"x\"}").with(as("ADMIN"))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("trip_departed"));
        mvc.perform(post("/admin/bookings/32/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}").with(as("ADMIN"))).andExpect(status().isNotFound());
    }
}
