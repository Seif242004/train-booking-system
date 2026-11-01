package com.trainbooking.trainschedule.trip.service;

import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.trip.model.BookingConflictException;
import com.trainbooking.trainschedule.trip.model.BookingSnapshot;
import com.trainbooking.trainschedule.trip.model.BookingSnapshotRejectedException;
import com.trainbooking.trainschedule.trip.model.BookingUnavailableException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls the internal endpoints of Booking and Seat with the shared API key. Every failure becomes one of three
 * exceptions, so the caller (and the admin who sees the answer) can tell a refusal from an outage from a bug:
 * 409 is {@link BookingConflictException}, 400 is {@link BookingSnapshotRejectedException}, and anything else
 * (no connection, timeout, 401, 5xx) is {@link BookingUnavailableException}.
 */
@Component
class BookingSeatClient {

    private static final Logger log = LoggerFactory.getLogger(BookingSeatClient.class);

    private final RestClient http;
    private final String apiKey;

    BookingSeatClient(@Qualifier("bookingSeatRestClient") RestClient http, @Value("${trainbooking.internal-api-key}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_KEY must be set: it authorises the calls to Booking and Seat.");
        }
        this.http = http;
        this.apiKey = apiKey;
    }

    /** Stores or updates the copy of the trip. Returns normally only if Booking accepted the whole snapshot. */
    void putSnapshot(long tripId, BookingSnapshot snapshot, String adminId) {
        send(() -> http.put().uri("/internal/trips/{id}/snapshot", tripId)
                .header("X-Internal-Api-Key", apiKey).header("X-Acting-User", adminId)
                .body(snapshot).retrieve().toBodilessEntity(), "push the snapshot of trip " + tripId);
    }

    /** Removes the copy of a trip that is deleted here. Booking refuses (409) if the trip has holds or bookings. */
    void deleteTrip(long tripId, String adminId) {
        send(() -> http.delete().uri("/internal/trips/{id}", tripId)
                .header("X-Internal-Api-Key", apiKey).header("X-Acting-User", adminId)
                .retrieve().toBodilessEntity(), "delete trip " + tripId);
    }

    private void send(Runnable call, String what) {
        try {
            call.run();
        } catch (RestClientResponseException e) {
            throw translate(e, what);
        } catch (ResourceAccessException e) { // no connection, connection refused, or a timeout
            log.warn("Booking and Seat could not be reached to {}: {}", what, e.getMessage());
            throw new BookingUnavailableException("Booking and Seat could not be reached.", e);
        }
    }

    private RuntimeException translate(RestClientResponseException e, String what) {
        HttpStatusCode status = e.getStatusCode();
        ApiError error = readError(e);
        String message = error != null ? error.error().message() : "Booking and Seat answered " + status.value() + ".";
        List<ApiError.Detail> details = error != null && error.error().details() != null ? error.error().details() : List.of();
        if (status.value() == 409) {
            return new BookingConflictException(message, details);
        }
        if (status.value() == 400) {
            log.error("Booking and Seat rejected the snapshot while trying to {}: {} {}", what, message, details);
            return new BookingSnapshotRejectedException(message, details);
        }
        // 401 means the two services do not share the same INTERNAL_API_KEY; 5xx is Booking failing. Neither is the admin's doing.
        log.error("Booking and Seat answered {} while trying to {}: {}", status.value(), what, message);
        return new BookingUnavailableException("Booking and Seat answered " + status.value() + ".", e);
    }

    private static ApiError readError(RestClientResponseException e) {
        try {
            ApiError error = e.getResponseBodyAs(ApiError.class);
            return error != null && error.error() != null ? error : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }
}
