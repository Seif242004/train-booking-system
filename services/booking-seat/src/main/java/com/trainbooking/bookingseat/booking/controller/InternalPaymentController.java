package com.trainbooking.bookingseat.booking.controller;

import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.service.PaymentResultService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** What Payment tells Booking. Service-to-service only (shared API key); the Gateway never routes {@code /internal}. */
// @RestController: only the Payment service calls these (header X-Internal-Api-Key, first security chain). They are the ONE way a booking
// becomes CONFIRMED by card, and the way the refund state is mirrored.
@RestController
public class InternalPaymentController {

    // Bean Validation: @DecimalMin the amount is at least 0.01; @Pattern the currency must be EGP. The amount is what Stripe really took.
    public record PaymentResult(
            Long paymentId,
            @NotNull(message = "is required") @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
            @Pattern(regexp = "EGP", message = "must be EGP") String currency) {
    }

    // Only the three refund states reported by Payment are accepted (the regex lists them).
    public record RefundStatusUpdate(
            @NotNull(message = "is required") @Pattern(regexp = "REFUND_PENDING|REFUNDED|REFUND_FAILED", message = "must be REFUND_PENDING, REFUNDED or REFUND_FAILED") String status) {
    }

    private final PaymentResultService results;

    public InternalPaymentController(PaymentResultService results) {
        this.results = results;
    }

    /** A payment succeeded: the booking is confirmed, or Payment is told to refund the money. */
    // Answer: { "result": CONFIRMED | REFUND_REQUIRED } (see PaymentResultService.Result). Idempotent: Payment's outbox
    // may send the same report twice.
    @PostMapping("/internal/bookings/{id}/payment-result")
    public Map<String, String> paymentResult(@PathVariable long id, @Valid @RequestBody PaymentResult body) {
        return Map.of("result", results.paymentSucceeded(id, body.amount()).name());
    }

    // Mirrors REFUND_PENDING, REFUNDED or REFUND_FAILED into booking.refund_status.
    @PostMapping("/internal/bookings/{id}/refund-status")
    public Map<String, String> refundStatus(@PathVariable long id, @Valid @RequestBody RefundStatusUpdate body) {
        results.refundStatus(id, RefundStatus.valueOf(body.status()));
        return Map.of("status", body.status());
    }
}
