package com.trainbooking.trainschedule.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.trip.model.BookingConflictException;
import com.trainbooking.trainschedule.trip.model.BookingSnapshot;
import com.trainbooking.trainschedule.trip.model.BookingSnapshotRejectedException;
import com.trainbooking.trainschedule.trip.model.BookingUnavailableException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/** What goes over the wire to Booking and Seat, and how each kind of answer is understood. */
class BookingSeatClientTest {

    private static final BookingSnapshot SNAPSHOT = new BookingSnapshot(LocalDate.of(2026, 11, 2),
            new BookingSnapshot.Train("T100", "Express", "Express"), "Cairo-Alexandria",
            List.of(new BookingSnapshot.Stop(0, 1, "CAI", "Cairo", null, OffsetDateTime.parse("2026-11-02T08:00:00+02:00")),
                    new BookingSnapshot.Stop(1, 2, "ALX", "Alexandria", OffsetDateTime.parse("2026-11-02T10:45:00+02:00"), null)),
            List.of(new BookingSnapshot.Coach(10, "A", CoachClass.FIRST, 1, 3)),
            List.of(new BookingSnapshot.Seat(1, 10, "A1", 1, "A", CoachClass.FIRST)),
            List.of(new BookingSnapshot.Fare(0, CoachClass.FIRST, new BigDecimal("80.00"), "EGP")));

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://booking-seat");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final BookingSeatClient client = new BookingSeatClient(builder.build(), "secret-key");

    private static final String CONFLICT_BODY = """
            {"error":{"code":"capacity_conflict","message":"The change would remove capacity that has holds or bookings.",
             "details":[{"field":"seats","message":"seat B2 has holds or bookings and cannot be removed"}]}}
            """;

    @Test
    void putSnapshot_sendsTheWholeSnapshot_withTheKeyAndTheAdmin() {
        server.expect(requestTo("http://booking-seat/internal/trips/21/snapshot"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Internal-Api-Key", "secret-key"))
                .andExpect(header("X-Acting-User", "admin-sub"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.departureDate").value("2026-11-02"))
                .andExpect(jsonPath("$.train.number").value("T100"))
                .andExpect(jsonPath("$.stops[0].stationCode").value("CAI"))
                .andExpect(jsonPath("$.stops[0].arrivalTime").doesNotExist())
                .andExpect(jsonPath("$.stops[0].departureTime").value("2026-11-02T08:00:00+02:00"))
                .andExpect(jsonPath("$.coaches[0].class").value("FIRST"))
                .andExpect(jsonPath("$.coaches[0].standingCapacity").value(3))
                .andExpect(jsonPath("$.seats[0].coachId").value(10))
                .andExpect(jsonPath("$.seats[0].row").value(1))
                .andExpect(jsonPath("$.seats[0].column").value("A"))
                .andExpect(jsonPath("$.seats[0].class").value("FIRST"))
                .andExpect(jsonPath("$.fares[0].amount").value(80.00))
                .andRespond(withSuccess("{\"tripId\":21,\"created\":true}", MediaType.APPLICATION_JSON));

        client.putSnapshot(21, SNAPSHOT, "admin-sub");

        server.verify();
    }

    @Test
    void aConflict_isTranslated_keepingBookingsExplanationAndDetails() {
        server.expect(requestTo("http://booking-seat/internal/trips/21/snapshot"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(CONFLICT_BODY));

        assertThatThrownBy(() -> client.putSnapshot(21, SNAPSHOT, "admin-sub"))
                .isInstanceOf(BookingConflictException.class)
                .hasMessage("The change would remove capacity that has holds or bookings.")
                .satisfies(e -> assertThat(((BookingConflictException) e).details()).extracting(ApiError.Detail::message)
                        .containsExactly("seat B2 has holds or bookings and cannot be removed"));
    }

    @Test
    void aConflictWithoutAJsonBody_isStillAConflict() {
        server.expect(requestTo("http://booking-seat/internal/trips/21/snapshot")).andRespond(withStatus(HttpStatus.CONFLICT).body("busy"));

        assertThatThrownBy(() -> client.putSnapshot(21, SNAPSHOT, "admin-sub")).isInstanceOf(BookingConflictException.class);
    }

    @Test
    void aSnapshotThatBookingFindsInvalid_isAFaultOfOurData_notARetry() {
        server.expect(requestTo("http://booking-seat/internal/trips/21/snapshot")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"validation_failed\",\"message\":\"The request body is not valid.\",\"details\":[{\"field\":\"seats[0].class\",\"message\":\"must be the class of its coach (FIRST)\"}]}}"));

        assertThatThrownBy(() -> client.putSnapshot(21, SNAPSHOT, "admin-sub"))
                .isInstanceOf(BookingSnapshotRejectedException.class)
                .satisfies(e -> assertThat(((BookingSnapshotRejectedException) e).details()).extracting(ApiError.Detail::field).containsExactly("seats[0].class"));
    }

    @Test
    void aServerError_aWrongKey_andALostConnection_areAllAnOutage() {
        server.expect(requestTo("http://booking-seat/internal/trips/21/snapshot")).andRespond(withServerError());
        server.expect(requestTo("http://booking-seat/internal/trips/21/snapshot")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("http://booking-seat/internal/trips/21/snapshot"))
                .andRespond(request -> { throw new IOException("Connection refused"); });

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> client.putSnapshot(21, SNAPSHOT, "admin-sub")).isInstanceOf(BookingUnavailableException.class);
        }
        server.verify();
    }

    @Test
    void deleteTrip_sendsTheKey_andAcceptsNoContent() {
        server.expect(requestTo("http://booking-seat/internal/trips/21"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-Internal-Api-Key", "secret-key"))
                .andExpect(header("X-Acting-User", "admin-sub"))
                .andRespond(withNoContent());

        client.deleteTrip(21, "admin-sub");

        server.verify();
    }

    @Test
    void deleteTrip_ofATripWithReservations_isAConflict() {
        server.expect(requestTo("http://booking-seat/internal/trips/21")).andRespond(MockRestResponseCreators.withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"trip_has_reservations\",\"message\":\"Trip 21 has holds or bookings, so it cannot be deleted.\"}}"));

        assertThatThrownBy(() -> client.deleteTrip(21, "admin-sub"))
                .isInstanceOf(BookingConflictException.class)
                .hasMessage("Trip 21 has holds or bookings, so it cannot be deleted.");
    }

    @Test
    void withoutAKey_theClientDoesNotStart() {
        assertThatThrownBy(() -> new BookingSeatClient(builder.build(), " ")).isInstanceOf(IllegalStateException.class);
    }
}
