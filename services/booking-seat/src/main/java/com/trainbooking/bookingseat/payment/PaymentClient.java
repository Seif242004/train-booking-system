package com.trainbooking.bookingseat.payment;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls the internal endpoints of the Payment service with the shared API key. Every failure becomes one of two exceptions, so the
 * callers can tell a refusal from "the answer is not known": a 409 is {@link PaymentRejectedException}; no connection, a timeout, a
 * 5xx or an unexpected answer is {@link PaymentUnavailableException}, and then the caller must not assume anything about the payment
 * (the sweeper keeps the seats held and tries again).
 *
 * <p>The read timeout is longer than for other services because Payment calls Stripe before it answers.
 */
@Component
class PaymentClient implements PaymentPort, RefundPort {

    private final RestClient http;
    private final String apiKey;

    PaymentClient(@Value("${trainbooking.payment.url}") String baseUrl, @Value("${trainbooking.internal-api-key}") String apiKey,
                  @Value("${trainbooking.payment.read-timeout-ms:10000}") long readTimeoutMs) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
    }

    @Override
    public CancelOutcome cancelPending(long bookingId) {
        return outcome(send(() -> http.post().uri("/internal/payments/{id}/cancel", bookingId).header("X-Internal-Api-Key", apiKey)
                .retrieve().body(Map.class)));
    }

    @Override
    public CancelOutcome invalidate(long bookingId) {
        return outcome(send(() -> http.post().uri("/internal/payments/{id}/invalidate", bookingId).header("X-Internal-Api-Key", apiKey)
                .retrieve().body(Map.class)));
    }

    @Override
    public PaymentSession createPayment(long bookingId, BigDecimal amount, String currency) {
        Map<?, ?> answer = send(() -> http.post().uri("/internal/payments").header("X-Internal-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("bookingId", bookingId, "amount", amount, "currency", currency))
                .retrieve().body(Map.class));
        if (answer == null || answer.get("clientSecret") == null || answer.get("publishableKey") == null || answer.get("publishableKey").toString().isBlank()) {
            throw new PaymentUnavailableException("Payment answered without a client secret or a publishable key", null);
        }
        return new PaymentSession(answer.get("clientSecret").toString(), answer.get("publishableKey").toString(), amount, currency);
    }

    @Override
    public RefundPort.Result requestRefund(long bookingId, String reason) {
        try {
            send(() -> http.post().uri("/internal/refunds").header("X-Internal-Api-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                    .body(reason == null ? Map.of("bookingId", bookingId) : Map.of("bookingId", bookingId, "reason", reason)).retrieve().body(Map.class));
            return RefundPort.Result.REQUESTED;
        } catch (NoPaymentException e) {
            return RefundPort.Result.NO_PAYMENT;
        }
    }

    /** 404 {@code no_payment_to_refund} from Payment. */
    private static final class NoPaymentException extends RuntimeException {
    }

    private static CancelOutcome outcome(Map<?, ?> answer) {
        try {
            return CancelOutcome.valueOf(String.valueOf(answer == null ? null : answer.get("outcome")));
        } catch (IllegalArgumentException e) {
            throw new PaymentUnavailableException("Payment answered with an unknown outcome: " + answer, e);
        }
    }

    private static <T> T send(Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String code = errorCode(e);
            if (status == 404 && "no_payment_to_refund".equals(code)) {
                throw new NoPaymentException();
            }
            if (status == 409) {
                throw new PaymentRejectedException(code, "Payment refused: " + code);
            }
            if (status == 503 && "stripe_not_configured".equals(code)) {
                throw new PaymentUnavailableException("stripe_not_configured", "Stripe is not set up in this deployment.", e);
            }
            throw new PaymentUnavailableException("Payment answered " + status + " " + code, e);
        } catch (ResourceAccessException e) { // no connection, connection refused, or a timeout
            throw new PaymentUnavailableException("Payment could not be reached: " + e.getMessage(), e);
        }
    }

    /** The {@code error.code} of the shared error shape, or "unknown". */
    private static String errorCode(RestClientResponseException e) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            Object error = body == null ? null : body.get("error");
            return error instanceof Map<?, ?> map && map.get("code") != null ? map.get("code").toString() : "unknown";
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }
}
