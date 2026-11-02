package com.trainbooking.bookingseat.ticket;

import com.trainbooking.bookingseat.common.RemoteServiceException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Calls the internal endpoints of the Ticket service with the shared API key. */
@Component
class TicketClient implements TicketPort {

    private final RestClient http;
    private final String apiKey;

    TicketClient(@Value("${trainbooking.ticket.url}") String baseUrl, @Value("${trainbooking.internal-api-key}") String apiKey,
                 @Value("${trainbooking.ticket.read-timeout-ms:10000}") long readTimeoutMs) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
    }

    @Override
    public void issue(long bookingId, TicketData data) {
        send(() -> http.put().uri("/internal/tickets/{id}", bookingId).header("X-Internal-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON).body(data).retrieve().toBodilessEntity());
    }

    @Override
    public byte[] pdf(long bookingId) {
        byte[] pdf = send(() -> http.get().uri("/internal/tickets/{id}/pdf", bookingId).header("X-Internal-Api-Key", apiKey).retrieve().body(byte[].class));
        if (pdf == null || pdf.length == 0) {
            throw new RemoteServiceException("Ticket answered without a PDF");
        }
        return pdf;
    }

    @Override
    public void invalidate(long bookingId) {
        try {
            send(() -> http.post().uri("/internal/tickets/{id}/invalidate", bookingId).header("X-Internal-Api-Key", apiKey).retrieve().toBodilessEntity());
        } catch (NoTicketException e) {
            // There is no ticket to invalidate (it was never made): nothing to do.
        }
    }

    /** 404 from Ticket: it knows no ticket for the booking. */
    private static final class NoTicketException extends RuntimeException {
    }

    private static <T> T send(Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 && e.getResponseBodyAsString().contains("ticket_not_found")) {
                throw new NoTicketException();
            }
            throw new RemoteServiceException("Ticket answered " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) { // no connection, connection refused, or a timeout
            throw new RemoteServiceException("Ticket could not be reached: " + e.getMessage(), e);
        }
    }
}
