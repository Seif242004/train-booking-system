package com.trainbooking.bookingseat.booking.controller;

import static com.trainbooking.bookingseat.testsupport.TestJwts.passenger;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldConflictException;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.model.TripClosedException;
import com.trainbooking.bookingseat.booking.service.BookingQueryService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.GlobalExceptionHandler;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.security.JsonErrorHandlers;
import com.trainbooking.bookingseat.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = BookingController.class, properties = "trainbooking.internal-api-key=test-key")
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class BookingControllerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-01T10:00:00Z");
    private static final BookingView BOOKING = new BookingView(31, "TB-ABC234", BookingStatus.PENDING_PAYMENT, RefundStatus.NONE, 2, 0, 2,
            new BigDecimal("380.00"), "EGP", NOW.plusMinutes(5), NOW, NOW, null, List.of());
    private static final String BODY = "{\"tripId\":2,\"fromStop\":0,\"toStop\":2,\"seatIds\":[223,224],\"standing\":[{\"coachId\":33,\"qty\":2}]}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    HoldService holds;

    @MockitoBean
    BookingQueryService queries;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void withoutAToken_nothingHappens() throws Exception {
        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/bookings/31")).andExpect(status().isUnauthorized());
        verifyNoInteractions(holds, queries);
    }

    @Test
    void aHold_isCreated_withTheUserOfTheToken_andTheTimeItRunsOut() throws Exception {
        given(holds.hold(any(), any())).willReturn(BOOKING);

        mvc.perform(post("/bookings").with(jwt().jwt(t -> t.subject("alice-sub").claim("email", "alice@example.com").claim("name", "Alice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/bookings/31"))
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.reference").value("TB-ABC234"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.amount").value(380.00))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.serverTime").exists());

        ArgumentCaptor<AuthenticatedUser> user = ArgumentCaptor.forClass(AuthenticatedUser.class);
        ArgumentCaptor<HoldRequest> request = ArgumentCaptor.forClass(HoldRequest.class);
        verify(holds).hold(user.capture(), request.capture());
        org.assertj.core.api.Assertions.assertThat(user.getValue()).isEqualTo(new AuthenticatedUser("alice-sub", "alice@example.com", "Alice"));
        org.assertj.core.api.Assertions.assertThat(request.getValue().seats()).containsExactly(223L, 224L);
        org.assertj.core.api.Assertions.assertThat(request.getValue().standingPlaces()).hasSize(1);
    }

    @Test
    void theClientCannotSetTheAmount_aFieldOfThatNameIsIgnored() throws Exception {
        given(holds.hold(any(), any())).willReturn(BOOKING);

        mvc.perform(post("/bookings").with(passenger()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":2,\"fromStop\":0,\"toStop\":2,\"seatIds\":[223],\"amount\":1,\"price\":1}"))
                .andExpect(status().isCreated());
        // HoldRequest has no amount or price: there is nothing to read it into (see the record).
        org.assertj.core.api.Assertions.assertThat(java.util.Arrays.stream(HoldRequest.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("amount", "price");
    }

    @Test
    void missingFieldsAndTooManyItems_return400_namingTheFields() throws Exception {
        mvc.perform(post("/bookings").with(passenger()).contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[1],\"standing\":[{\"qty\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[?(@.field=='tripId')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='fromStop')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='toStop')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='standing[0].coachId')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='standing[0].qty')]").exists());
        verifyNoInteractions(holds);
    }

    @Test
    void refusalsOfTheService_areMappedToTheirStatusAndCode() throws Exception {
        org.mockito.Mockito.doThrow(new RequestValidationException(List.of(new ApiError.Detail("seatIds[0]", "seat 9 is not on this trip")))).when(holds).hold(any(), any());
        mvc.perform(post("/bookings").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("seatIds[0]"));

        org.mockito.Mockito.doThrow(new TripClosedException("trip_closed", "This trip is closed.")).when(holds).hold(any(), any());
        mvc.perform(post("/bookings").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("trip_closed"));

        org.mockito.Mockito.doThrow(new TripClosedException("trip_departed", "The train has already left the boarding stop.")).when(holds).hold(any(), any());
        mvc.perform(post("/bookings").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("trip_departed"));
    }

    @Test
    void aHoldThatLostSomeItems_returns409_listingEachOne() throws Exception {
        given(holds.hold(any(), any())).willThrow(new HoldConflictException(List.of(
                new HoldConflictException.Lost("SEAT", 224, "seat 1B of coach A1"), new HoldConflictException.Lost("STANDING", 33, "standing places of coach B2"))));

        mvc.perform(post("/bookings").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("seats_unavailable"))
                .andExpect(jsonPath("$.error.details[0].field").value("seat:224"))
                .andExpect(jsonPath("$.error.details[0].message").value("seat 1B of coach A1 is no longer available"))
                .andExpect(jsonPath("$.error.details[1].field").value("standing:33"));
    }

    @Test
    void readingABooking_isForItsOwnerOnly_anotherUsersBookingIsNotFound() throws Exception {
        given(queries.viewOwn(31, "user-sub")).willReturn(BOOKING);
        given(queries.viewOwn(32, "user-sub")).willThrow(new BookingNotFoundException(32));

        mvc.perform(get("/bookings/31").with(passenger())).andExpect(status().isOk()).andExpect(jsonPath("$.reference").value("TB-ABC234"));
        mvc.perform(get("/bookings/32").with(passenger())).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("booking_not_found"));
        verify(queries).viewOwn(eq(32L), eq("user-sub"));
    }
}
