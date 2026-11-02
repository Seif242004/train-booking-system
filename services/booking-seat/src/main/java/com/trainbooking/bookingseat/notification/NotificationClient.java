package com.trainbooking.bookingseat.notification;

import com.trainbooking.bookingseat.common.RemoteServiceException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Calls the internal endpoint of the Notification service with the shared API key. */
@Component
class NotificationClient implements NotificationPort {

    private final RestClient http;
    private final String apiKey;

    NotificationClient(@Value("${trainbooking.notification.url}") String baseUrl, @Value("${trainbooking.internal-api-key}") String apiKey,
                       @Value("${trainbooking.notification.read-timeout-ms:10000}") long readTimeoutMs) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
    }

    @Override
    public void send(Email email) {
        try {
            http.post().uri("/internal/notifications").header("X-Internal-Api-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                    .body(email).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new RemoteServiceException("Notification answered " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) { // no connection, connection refused, or a timeout
            throw new RemoteServiceException("Notification could not be reached: " + e.getMessage(), e);
        }
    }
}
