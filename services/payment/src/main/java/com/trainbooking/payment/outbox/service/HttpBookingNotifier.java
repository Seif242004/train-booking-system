package com.trainbooking.payment.outbox.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Calls the internal endpoints of Booking and Seat with the shared API key. */
@Component
@ConditionalOnMissingBean(value = BookingNotifier.class, ignored = HttpBookingNotifier.class)
public class HttpBookingNotifier implements BookingNotifier {

    private final RestClient http;
    private final String apiKey;

    public HttpBookingNotifier(@Value("${trainbooking.booking-seat.url}") String baseUrl, @Value("${trainbooking.internal-api-key}") String apiKey) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
    }

    @Override
    public PaymentAnswer paymentResult(long bookingId, String payloadJson) {
        Map<?, ?> answer = send(() -> http.post().uri("/internal/bookings/{id}/payment-result", bookingId)
                .header("X-Internal-Api-Key", apiKey).contentType(MediaType.APPLICATION_JSON).body(payloadJson)
                .retrieve().body(Map.class));
        return answer != null && "REFUND_REQUIRED".equals(answer.get("result")) ? PaymentAnswer.REFUND_REQUIRED : PaymentAnswer.CONFIRMED;
    }

    @Override
    public void refundStatus(long bookingId, String payloadJson) {
        send(() -> http.post().uri("/internal/bookings/{id}/refund-status", bookingId)
                .header("X-Internal-Api-Key", apiKey).contentType(MediaType.APPLICATION_JSON).body(payloadJson)
                .retrieve().toBodilessEntity());
    }

    private <T> T send(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            // 404 and 400: Booking understood the message and will never accept it. Anything else (401, 5xx) may pass: try again.
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 400) {
                throw new BookingRejectedException("Booking answered " + e.getStatusCode().value());
            }
            throw new BookingUnavailableException("Booking answered " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new BookingUnavailableException("Booking could not be reached", e);
        }
    }
}
