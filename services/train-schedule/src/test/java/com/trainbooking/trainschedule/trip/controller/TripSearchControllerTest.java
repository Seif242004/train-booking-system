package com.trainbooking.trainschedule.trip.controller;

import static com.trainbooking.trainschedule.testsupport.TestJwts.admin;
import static com.trainbooking.trainschedule.testsupport.TestJwts.passenger;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.GlobalExceptionHandler;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import com.trainbooking.trainschedule.station.model.Station;
import com.trainbooking.trainschedule.trip.model.JourneyFare;
import com.trainbooking.trainschedule.trip.model.TripSearchResponse;
import com.trainbooking.trainschedule.trip.model.TripSearchResult;
import com.trainbooking.trainschedule.trip.model.TripStop;
import com.trainbooking.trainschedule.trip.service.TripSearchService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TripSearchController.class)
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class TripSearchControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final TripSearchResult RESULT = new TripSearchResult(21, "T100", "Cairo-Alexandria Express", "Express",
            "Cairo-Alexandria", new Station(1, "CAI", "Cairo", "Cairo"), new Station(3, "ALX", "Alexandria", "Alexandria"), 0, 2,
            LocalDateTime.of(2026, 11, 2, 8, 0), LocalDateTime.of(2026, 11, 2, 10, 45),
            List.of(new TripStop(0, 1, "CAI", "Cairo", null, LocalDateTime.of(2026, 11, 2, 8, 0)),
                    new TripStop(1, 2, "GIZ", "Giza", LocalDateTime.of(2026, 11, 2, 8, 40), LocalDateTime.of(2026, 11, 2, 8, 45)),
                    new TripStop(2, 3, "ALX", "Alexandria", LocalDateTime.of(2026, 11, 2, 10, 45), null)),
            List.of(new JourneyFare(CoachClass.FIRST, new BigDecimal("230.00"), "EGP")));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TripSearchService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void withoutAToken_returns401() throws Exception {
        mvc.perform(get("/trips").param("from", "CAI").param("to", "ALX").param("date", "2026-11-02"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void anOrdinaryPassenger_canSearch_andSeesTimesAndFaresPerClass() throws Exception {
        given(service.search("CAI", "ALX", DAY)).willReturn(new TripSearchResponse(List.of(RESULT), null));

        mvc.perform(get("/trips").param("from", "CAI").param("to", "ALX").param("date", "2026-11-02").with(passenger()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trips[0].tripId").value(21))
                .andExpect(jsonPath("$.trips[0].from.code").value("CAI"))
                .andExpect(jsonPath("$.trips[0].to.name").value("Alexandria"))
                .andExpect(jsonPath("$.trips[0].fromStopIndex").value(0))
                .andExpect(jsonPath("$.trips[0].toStopIndex").value(2))
                .andExpect(jsonPath("$.trips[0].departureTime").value("2026-11-02T08:00:00"))
                .andExpect(jsonPath("$.trips[0].arrivalTime").value("2026-11-02T10:45:00"))
                .andExpect(jsonPath("$.trips[0].stops.length()").value(3))
                .andExpect(jsonPath("$.trips[0].stops[0].stopIndex").value(0))
                .andExpect(jsonPath("$.trips[0].stops[0].stationCode").value("CAI"))
                .andExpect(jsonPath("$.trips[0].stops[0].arrivalTime").doesNotExist())
                .andExpect(jsonPath("$.trips[0].stops[0].departureTime").value("2026-11-02T08:00:00"))
                .andExpect(jsonPath("$.trips[0].stops[1].stationName").value("Giza"))
                .andExpect(jsonPath("$.trips[0].stops[1].arrivalTime").value("2026-11-02T08:40:00"))
                .andExpect(jsonPath("$.trips[0].stops[2].stationCode").value("ALX"))
                .andExpect(jsonPath("$.trips[0].fares[0].class").value("FIRST"))
                .andExpect(jsonPath("$.trips[0].fares[0].amount").value(230.00))
                .andExpect(jsonPath("$.trips[0].fares[0].currency").value("EGP"))
                .andExpect(jsonPath("$.nextAvailableDate").doesNotHaveJsonPath());
    }

    @Test
    void withNoTrips_theNextAvailableDateIsIncluded_whenThereIsOne() throws Exception {
        given(service.search("CAI", "ALX", DAY)).willReturn(new TripSearchResponse(List.of(), LocalDate.of(2026, 11, 9)));

        mvc.perform(get("/trips").param("from", "CAI").param("to", "ALX").param("date", "2026-11-02").with(passenger()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trips.length()").value(0))
                .andExpect(jsonPath("$.nextAvailableDate").value("2026-11-09"));
    }

    @Test
    void anAdmin_canSearchToo() throws Exception {
        given(service.search("CAI", "ALX", DAY)).willReturn(new TripSearchResponse(List.of(), null));

        mvc.perform(get("/trips").param("from", "CAI").param("to", "ALX").param("date", "2026-11-02").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trips.length()").value(0))
                .andExpect(jsonPath("$.nextAvailableDate").doesNotHaveJsonPath()); // omitted, not null, when there is none
    }

    @Test
    void aMissingParameter_returns400_namingIt() throws Exception {
        mvc.perform(get("/trips").param("from", "CAI").param("date", "2026-11-02").with(passenger()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("missing_parameter"))
                .andExpect(jsonPath("$.error.message").value("The parameter 'to' is required."));
        verifyNoInteractions(service);
    }

    @Test
    void aDateThatIsNotADate_returns400() throws Exception {
        mvc.perform(get("/trips").param("from", "CAI").param("to", "ALX").param("date", "next-friday").with(passenger()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_parameter"));
        verifyNoInteractions(service);
    }

    @Test
    void anUnknownStation_returns400_namingTheParameter() throws Exception {
        given(service.search("CAI", "XXX", DAY)).willThrow(new RequestValidationException(
                List.of(new ApiError.Detail("to", "unknown station code XXX"))));

        mvc.perform(get("/trips").param("from", "CAI").param("to", "XXX").param("date", "2026-11-02").with(passenger()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("to"));
    }
}
