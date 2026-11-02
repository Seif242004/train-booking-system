package com.trainbooking.bookingseat.schedule;

import com.trainbooking.bookingseat.common.RemoteServiceException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Calls the internal endpoint of Train & Schedule with the shared API key. */
@Component
class TrainScheduleClient implements TrainSchedulePort {

    private final RestClient http;
    private final String apiKey;

    TrainScheduleClient(@Value("${trainbooking.train-schedule.url}") String baseUrl, @Value("${trainbooking.internal-api-key}") String apiKey,
                        @Value("${trainbooking.train-schedule.read-timeout-ms:5000}") long readTimeoutMs) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
    }

    @Override
    public void markTripCancelled(long tripId, String adminId) {
        try {
            http.post().uri("/internal/trips/{id}/cancel", tripId).header("X-Internal-Api-Key", apiKey).header("X-Acting-User", adminId == null ? "booking-seat" : adminId)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new RemoteServiceException("Train & Schedule answered " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) { // no connection, connection refused, or a timeout
            throw new RemoteServiceException("Train & Schedule could not be reached: " + e.getMessage(), e);
        }
    }
}
