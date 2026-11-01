package com.trainbooking.bookingseat.availability.controller;

import static com.trainbooking.bookingseat.testsupport.TestJwts.passenger;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.bookingseat.availability.model.CoachStandingLeft;
import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.availability.model.JourneyAvailability;
import com.trainbooking.bookingseat.availability.model.SeatState;
import com.trainbooking.bookingseat.availability.model.SeatStatus;
import com.trainbooking.bookingseat.availability.model.TripAvailability;
import com.trainbooking.bookingseat.availability.service.AvailabilityService;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.CoachClass;
import com.trainbooking.bookingseat.common.GlobalExceptionHandler;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import com.trainbooking.bookingseat.security.JsonErrorHandlers;
import com.trainbooking.bookingseat.security.SecurityConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = AvailabilityController.class, properties = "trainbooking.internal-api-key=test-key")
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class AvailabilityControllerTest {

    private static final TripAvailability AVAILABILITY = new TripAvailability(21, "OPEN", new JourneyAvailability(
            new Journey(1, 3),
            List.of(new SeatStatus(1, 10, CoachClass.FIRST, SeatState.AVAILABLE), new SeatStatus(2, 20, CoachClass.SECOND, SeatState.HELD),
                    new SeatStatus(3, 20, CoachClass.SECOND, SeatState.BOOKED)),
            List.of(new CoachStandingLeft(20, CoachClass.SECOND, 10, 4)),
            Map.of(CoachClass.FIRST, 1, CoachClass.SECOND, 0),
            Map.of(CoachClass.SECOND, 4)));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AvailabilityService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void withoutAToken_returns401() throws Exception {
        mvc.perform(get("/trips/21/availability").param("from", "1").param("to", "3"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void anOrdinaryPassenger_getsTheSeatStatesAndTheStandingPlacesOfTheJourney() throws Exception {
        given(service.availability(21, 1, 3)).willReturn(AVAILABILITY);

        mvc.perform(get("/trips/21/availability").param("from", "1").param("to", "3").with(passenger()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(21))
                .andExpect(jsonPath("$.tripStatus").value("OPEN"))
                .andExpect(jsonPath("$.fromStop").value(1))
                .andExpect(jsonPath("$.toStop").value(3))
                .andExpect(jsonPath("$.seats.length()").value(3))
                .andExpect(jsonPath("$.seats[1].seatId").value(2))
                .andExpect(jsonPath("$.seats[1].coachId").value(20))
                .andExpect(jsonPath("$.seats[1].state").value("HELD"))
                .andExpect(jsonPath("$.seats[2].state").value("BOOKED"))
                .andExpect(jsonPath("$.standing[0].coachId").value(20))
                .andExpect(jsonPath("$.standing[0].capacity").value(10))
                .andExpect(jsonPath("$.standing[0].remaining").value(4))
                .andExpect(jsonPath("$.summary.seatsLeft.FIRST").value(1))
                .andExpect(jsonPath("$.summary.seatsLeft.SECOND").value(0))
                .andExpect(jsonPath("$.summary.standingLeft.SECOND").value(4));
    }

    @Test
    void theSummaryView_hasTheTotalsPerClassOnly() throws Exception {
        given(service.availability(21, 1, 3)).willReturn(AVAILABILITY);

        mvc.perform(get("/trips/21/availability").param("from", "1").param("to", "3").param("view", "summary").with(passenger()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.seatsLeft.FIRST").value(1))
                .andExpect(jsonPath("$.summary.standingLeft.SECOND").value(4))
                .andExpect(jsonPath("$.seats").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.standing").doesNotHaveJsonPath());
    }

    @Test
    void aViewThatIsNeitherFullNorSummary_returns400() throws Exception {
        mvc.perform(get("/trips/21/availability").param("from", "1").param("to", "3").param("view", "everything").with(passenger()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[0].field").value("view"));
        verifyNoInteractions(service);
    }

    @Test
    void aJourneyThatTheServiceRejects_returns400_namingTheField() throws Exception {
        given(service.availability(21, 3, 1)).willThrow(new RequestValidationException(List.of(new ApiError.Detail("to", "must be a stop after from"))));

        mvc.perform(get("/trips/21/availability").param("from", "3").param("to", "1").with(passenger()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("to"));
    }

    @Test
    void aMissingOrNonNumericStop_returns400() throws Exception {
        mvc.perform(get("/trips/21/availability").param("from", "1").with(passenger()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("missing_parameter"))
                .andExpect(jsonPath("$.error.message").value("The parameter 'to' is required."));
        mvc.perform(get("/trips/21/availability").param("from", "one").param("to", "3").with(passenger()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_parameter"));
        verifyNoInteractions(service);
    }

    @Test
    void aTripThatBookingDoesNotHave_returns404() throws Exception {
        given(service.availability(99, 0, 1)).willThrow(new TripNotFoundException(99));

        mvc.perform(get("/trips/99/availability").param("from", "0").param("to", "1").with(passenger()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("trip_not_found"));
    }
}
