package com.trainbooking.ticket.ticket.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What the ticket shows (SRS FR-T2). Booking and Seat sends it when the booking is confirmed: everything on the ticket comes from the
 * booking, so this service needs no other data. The price is the amount the booking was paid, as the server worked it out.
 */
public record TicketRequest(
        @NotBlank(message = "is required") @Size(max = 20, message = "must be at most 20 characters") String reference,
        @NotBlank(message = "is required") @Size(max = 200, message = "must be at most 200 characters") String passengerName,
        @NotBlank(message = "is required") @Size(max = 50, message = "must be at most 50 characters") String trainNumber,
        @Size(max = 200, message = "must be at most 200 characters") String trainName,
        @NotBlank(message = "is required") @Size(max = 200, message = "must be at most 200 characters") String fromStation,
        @NotNull(message = "is required") OffsetDateTime departure,
        @NotBlank(message = "is required") @Size(max = 200, message = "must be at most 200 characters") String toStation,
        @NotNull(message = "is required") OffsetDateTime arrival,
        @NotEmpty(message = "is required") @Size(max = 60, message = "must have at most 60 lines") List<@Valid Line> lines,
        @NotNull(message = "is required") @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
        @NotBlank(message = "is required") @Pattern(regexp = "EGP", message = "must be EGP") String currency) {

    /** One seat, or the standing places of one coach. */
    public record Line(
            @NotNull(message = "is required") @Pattern(regexp = "SEAT|STANDING", message = "must be SEAT or STANDING") String type,
            @NotBlank(message = "is required") @Size(max = 20, message = "must be at most 20 characters") String coachCode,
            @NotBlank(message = "is required") @Size(max = 20, message = "must be at most 20 characters") String coachClass,
            @Size(max = 20, message = "must be at most 20 characters") String seatNumber,
            @Min(value = 1, message = "must be at least 1") int qty,
            @NotNull(message = "is required") @DecimalMin(value = "0.00", message = "must not be negative") BigDecimal price) {
    }
}
