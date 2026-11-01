package com.trainbooking.payment.payment.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Booking and Seat asks for a payment. The amount is the one Booking worked out on the server from the fares; Payment never takes an
 * amount from a browser, because the browser never talks to Payment.
 */
public record CreatePaymentRequest(
        @NotNull(message = "is required") @Positive(message = "must be above 0") Long bookingId,
        @NotNull(message = "is required") @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimals") BigDecimal amount,
        @NotBlank(message = "is required") @Pattern(regexp = "EGP", message = "must be EGP") String currency) {
}
