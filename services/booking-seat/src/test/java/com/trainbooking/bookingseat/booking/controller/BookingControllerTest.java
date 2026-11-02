package com.trainbooking.bookingseat.booking.controller;

import static com.trainbooking.bookingseat.testsupport.TestJwts.passenger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldConflictException;
import com.trainbooking.bookingseat.booking.model.HoldExpiredException;
import com.trainbooking.bookingseat.booking.model.PickRequest;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.model.ReleaseRequest;
import com.trainbooking.bookingseat.booking.model.TripClosedException;
import com.trainbooking.bookingseat.booking.service.BookingQueryService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.booking.service.PaymentStartService;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import com.trainbooking.bookingseat.booking.model.MyBooking;
import com.trainbooking.bookingseat.booking.service.TicketResendService;
import com.trainbooking.bookingseat.booking.model.PaymentStart;
import com.trainbooking.bookingseat.payment.PaymentRejectedException;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.GlobalExceptionHandler;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.payment.PaymentUnavailableException;
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
            new BigDecimal("230.00"), "EGP", NOW.plusMinutes(5), NOW, NOW, null, List.of());
    private static final String SEAT = "{\"tripId\":2,\"fromStop\":0,\"toStop\":2,\"seatId\":223}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    HoldService holds;

    @MockitoBean
    BookingQueryService queries;

    @MockitoBean
    PaymentStartService paymentStarts;

    @MockitoBean
    TicketResendService resends;

    @MockitoBean
    CancellationService cancellations;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void withoutAToken_nothingHappens() throws Exception {
        mvc.perform(post("/bookings/pick").contentType(MediaType.APPLICATION_JSON).content(SEAT)).andExpect(status().isUnauthorized());
        mvc.perform(post("/bookings/31/release").contentType(MediaType.APPLICATION_JSON).content("{\"seatId\":223}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/bookings/31/hold")).andExpect(status().isUnauthorized());
        mvc.perform(get("/bookings/31")).andExpect(status().isUnauthorized());
        mvc.perform(post("/bookings/31/payment")).andExpect(status().isUnauthorized());
        mvc.perform(post("/bookings/31/resend-ticket")).andExpect(status().isUnauthorized());
        mvc.perform(post("/bookings/31/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(get("/bookings")).andExpect(status().isUnauthorized());
        verifyNoInteractions(holds, queries, paymentStarts, resends, cancellations);
    }

    @Test
    void theFirstPick_isCreated_withTheUserOfTheToken_andTheTimeTheHoldRunsOut() throws Exception {
        given(holds.pick(any(), any())).willReturn(new HoldService.PickResult(BOOKING, true));

        mvc.perform(post("/bookings/pick").with(jwt().jwt(t -> t.subject("alice-sub").claim("email", "alice@example.com").claim("name", "Alice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(SEAT))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/bookings/31"))
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.reference").value("TB-ABC234"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.serverTime").exists());

        ArgumentCaptor<AuthenticatedUser> user = ArgumentCaptor.forClass(AuthenticatedUser.class);
        ArgumentCaptor<PickRequest> request = ArgumentCaptor.forClass(PickRequest.class);
        verify(holds).pick(user.capture(), request.capture());
        assertThat(user.getValue()).isEqualTo(new AuthenticatedUser("alice-sub", "alice@example.com", "Alice"));
        assertThat(request.getValue().seatId()).isEqualTo(223L);
        assertThat(request.getValue().isSeat()).isTrue();
    }

    @Test
    void aFurtherPick_joinsTheHold_with200() throws Exception {
        given(holds.pick(any(), any())).willReturn(new HoldService.PickResult(BOOKING, false));

        mvc.perform(post("/bookings/pick").with(passenger()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":2,\"fromStop\":0,\"toStop\":2,\"coachId\":33,\"qty\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(31));
    }

    @Test
    void theClientCannotSetTheAmount_thereIsNoSuchField() {
        assertThat(java.util.Arrays.stream(PickRequest.class.getRecordComponents()).map(c -> c.getName())).doesNotContain("amount", "price");
        assertThat(java.util.Arrays.stream(ReleaseRequest.class.getRecordComponents()).map(c -> c.getName())).doesNotContain("amount", "price");
    }

    @Test
    void missingFieldsAndOutOfRangeQuantities_return400_namingTheFields() throws Exception {
        mvc.perform(post("/bookings/pick").with(passenger()).contentType(MediaType.APPLICATION_JSON).content("{\"coachId\":3,\"qty\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[?(@.field=='tripId')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='fromStop')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='toStop')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='qty')]").exists());
        verifyNoInteractions(holds);
    }

    @Test
    void refusalsOfTheService_areMappedToTheirStatusAndCode() throws Exception {
        doThrow(new RequestValidationException(List.of(new ApiError.Detail("seatId", "seat 9 is not on this trip")))).when(holds).pick(any(), any());
        mvc.perform(post("/bookings/pick").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(SEAT))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("seatId"));

        doThrow(new TripClosedException("trip_closed", "This trip is closed.")).when(holds).pick(any(), any());
        mvc.perform(post("/bookings/pick").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(SEAT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("trip_closed"));

        doThrow(new BookingConflictException("hold_for_another_journey", "You are already holding seats for another journey.")).when(holds).pick(any(), any());
        mvc.perform(post("/bookings/pick").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(SEAT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("hold_for_another_journey"));

        doThrow(new HoldExpiredException()).when(holds).pick(any(), any());
        mvc.perform(post("/bookings/pick").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(SEAT))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("hold_expired"))
                .andExpect(jsonPath("$.error.message").value("Your 5 minutes ran out. The seats were released, please select again."));

        doThrow(new PaymentUnavailableException("down", new RuntimeException())).when(holds).pick(any(), any());
        mvc.perform(post("/bookings/pick").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(SEAT))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("payment_unavailable"));
    }

    @Test
    void aPickThatLostARace_returns409_namingTheLostItem_andTheHeldSeatsAreNotMentioned() throws Exception {
        doThrow(new HoldConflictException(List.of(new HoldConflictException.Lost("SEAT", 224, "seat 1B of coach A1")))).when(holds).pick(any(), any());

        mvc.perform(post("/bookings/pick").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(SEAT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("seats_unavailable"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value("seat:224"))
                .andExpect(jsonPath("$.error.details[0].message").value("seat 1B of coach A1 is no longer available"));
    }

    @Test
    void releasingASeat_andReleasingEverything() throws Exception {
        given(holds.release(any(), eq(31L), any())).willReturn(BOOKING);
        given(holds.releaseAll(any(), eq(31L))).willReturn(BOOKING);

        mvc.perform(post("/bookings/31/release").with(passenger()).contentType(MediaType.APPLICATION_JSON).content("{\"seatId\":223}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(31));
        mvc.perform(post("/bookings/31/release").with(passenger()).contentType(MediaType.APPLICATION_JSON).content("{\"coachId\":33,\"qty\":2}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/bookings/31/hold").with(passenger())).andExpect(status().isOk()).andExpect(jsonPath("$.reference").value("TB-ABC234"));

        ArgumentCaptor<ReleaseRequest> request = ArgumentCaptor.forClass(ReleaseRequest.class);
        verify(holds, org.mockito.Mockito.times(2)).release(any(), eq(31L), request.capture());
        assertThat(request.getAllValues().get(1).coachId()).isEqualTo(33L);
        assertThat(request.getAllValues().get(1).places()).isEqualTo(2);
    }

    @Test
    void releasingSomethingThatIsNotThere_isRefused() throws Exception {
        doThrow(new BookingConflictException("not_picked", "That seat is not in your hold.")).when(holds).release(any(), eq(31L), any());
        doThrow(new BookingNotFoundException(32)).when(holds).releaseAll(any(), eq(32L));

        mvc.perform(post("/bookings/31/release").with(passenger()).contentType(MediaType.APPLICATION_JSON).content("{\"seatId\":9}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("not_picked"));
        mvc.perform(delete("/bookings/32/hold").with(passenger())).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("booking_not_found"));
    }

    @Test
    void readingABooking_isForItsOwnerOnly_anotherUsersBookingIsNotFound() throws Exception {
        given(queries.viewOwn(31, "user-sub")).willReturn(BOOKING);
        given(queries.viewOwn(32, "user-sub")).willThrow(new BookingNotFoundException(32));

        mvc.perform(get("/bookings/31").with(passenger())).andExpect(status().isOk()).andExpect(jsonPath("$.reference").value("TB-ABC234"));
        mvc.perform(get("/bookings/32").with(passenger())).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("booking_not_found"));
    }

    @Test
    void continueToPayment_returnsTheSecretAndTheTimeLeft_forTheUserOfTheToken() throws Exception {
        given(paymentStarts.start("user-sub", 31)).willReturn(new PaymentStart("pi_1_secret", "pk_test_x", new BigDecimal("230.00"), "EGP", NOW.plusMinutes(4), NOW.plusMinutes(1)));

        mvc.perform(post("/bookings/31/payment").with(passenger()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").value("pi_1_secret"))
                .andExpect(jsonPath("$.publishableKey").value("pk_test_x"))
                .andExpect(jsonPath("$.amount").value(230.00))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    void continueToPayment_failuresHaveTheirOwnStatus() throws Exception {
        given(paymentStarts.start("user-sub", 31)).willThrow(new HoldExpiredException());
        given(paymentStarts.start("user-sub", 32)).willThrow(new BookingNotFoundException(32));
        given(paymentStarts.start("user-sub", 33)).willThrow(new PaymentRejectedException("payment_closed", "x"));
        given(paymentStarts.start("user-sub", 34)).willThrow(new PaymentUnavailableException("down", null));
        given(paymentStarts.start("user-sub", 35)).willThrow(new PaymentUnavailableException("stripe_not_configured", "no key", null));

        mvc.perform(post("/bookings/31/payment").with(passenger())).andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("hold_expired"));
        mvc.perform(post("/bookings/32/payment").with(passenger())).andExpect(status().isNotFound());
        mvc.perform(post("/bookings/33/payment").with(passenger())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("payment_closed"));
        mvc.perform(post("/bookings/34/payment").with(passenger())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("payment_unavailable"));
        mvc.perform(post("/bookings/35/payment").with(passenger())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("stripe_not_configured"));
    }

    @Test
    void resendTicket_isAcceptedForTheUserOfTheToken_andSaysWhetherAnEmailWasQueued() throws Exception {
        given(resends.resend("user-sub", 31)).willReturn(true);
        given(resends.resend("user-sub", 32)).willReturn(false);
        given(resends.resend("user-sub", 33)).willThrow(new BookingConflictException("not_confirmed", "Only a confirmed booking has a ticket."));
        given(resends.resend("user-sub", 34)).willThrow(new BookingNotFoundException(34));

        mvc.perform(post("/bookings/31/resend-ticket").with(passenger())).andExpect(status().isAccepted()).andExpect(jsonPath("$.queued").value(true));
        mvc.perform(post("/bookings/32/resend-ticket").with(passenger())).andExpect(status().isAccepted()).andExpect(jsonPath("$.queued").value(false));
        mvc.perform(post("/bookings/33/resend-ticket").with(passenger())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("not_confirmed"));
        mvc.perform(post("/bookings/34/resend-ticket").with(passenger())).andExpect(status().isNotFound());
    }

    @Test
    void cancel_runsTheCancellationAsTheUserOfTheToken_withNoBody() throws Exception {
        given(cancellations.cancel(org.mockito.ArgumentMatchers.eq(CancellationService.Actor.user("user-sub")), eq(31L), org.mockito.ArgumentMatchers.isNull())).willReturn(BOOKING);

        mvc.perform(post("/bookings/31/cancel").with(passenger())).andExpect(status().isOk()).andExpect(jsonPath("$.reference").value("TB-ABC234"));
    }

    @Test
    void cancel_failuresHaveTheirOwnStatus() throws Exception {
        given(cancellations.cancel(any(), eq(32L), any())).willThrow(new BookingNotFoundException(32));
        given(cancellations.cancel(any(), eq(33L), any())).willThrow(new BookingConflictException("trip_departed", "The train has already left your boarding stop."));
        given(cancellations.cancel(any(), eq(34L), any())).willThrow(new BookingConflictException("not_cancellable", "Only a confirmed booking can be cancelled."));

        mvc.perform(post("/bookings/32/cancel").with(passenger())).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("booking_not_found"));
        mvc.perform(post("/bookings/33/cancel").with(passenger())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("trip_departed"));
        mvc.perform(post("/bookings/34/cancel").with(passenger())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("not_cancellable"));
    }

    @Test
    void myBookings_areThoseOfTheUserOfTheToken() throws Exception {
        given(queries.listOwn("user-sub")).willReturn(List.of(new MyBooking(BOOKING, true)));

        mvc.perform(get("/bookings").with(passenger())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].booking.reference").value("TB-ABC234")).andExpect(jsonPath("$[0].cancellable").value(true));
    }

    @Test
    void theOldConfirmSelectionEndpoint_isGone() throws Exception {
        mvc.perform(post("/bookings").with(passenger()).contentType(MediaType.APPLICATION_JSON).content("{\"tripId\":2,\"fromStop\":0,\"toStop\":2,\"seatIds\":[223]}"))
                .andExpect(status().isMethodNotAllowed()); // POST /bookings does not exist any more; /bookings is only the list (GET)
        verifyNoInteractions(holds);
    }
}
