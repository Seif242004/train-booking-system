package com.trainbooking.notification.notification.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A request for one email (ticket, cancellation or refund) from Booking and Seat. The service builds the text itself, so the callers only
 * say what happened. {@code dedupeKey} is chosen by the caller (for example {@code ticket:42:1}): asking again with the same key stores no
 * second email, so the caller can repeat a request whose answer it did not get.
 */
public record NotificationRequest(
        @NotBlank(message = "is required") @Size(max = 100, message = "must be at most 100 characters") String dedupeKey,
        @NotNull(message = "is required") Kind kind,
        @NotBlank(message = "is required") @Email(message = "must be an email address") @Size(max = 320, message = "must be at most 320 characters") String to,
        @Size(max = 200, message = "must be at most 200 characters") String toName,
        @NotBlank(message = "is required") @Size(max = 20, message = "must be at most 20 characters") String reference,
        @Valid Trip trip,
        @Size(max = 60, message = "must have at most 60 lines") List<@Size(max = 300, message = "must be at most 300 characters") String> lines,
        @DecimalMin(value = "0.00", message = "must not be negative") BigDecimal amount,
        @Pattern(regexp = "EGP", message = "must be EGP") String currency,
        @Size(max = 500, message = "must be at most 500 characters") String detail,
        @Valid Attachment attachment,
        @Pattern(regexp = "CARD|COUNTER|COMPLIMENTARY", message = "must be CARD, COUNTER or COMPLIMENTARY") String paymentMethod) {

    /** A card payment (the original shape). */
    public NotificationRequest(String dedupeKey, Kind kind, String to, String toName, String reference, Trip trip, List<String> lines, BigDecimal amount,
                               String currency, String detail, Attachment attachment) {
        this(dedupeKey, kind, to, toName, reference, trip, lines, amount, currency, detail, attachment, "CARD");
    }

    /** CARD unless the booking was issued at the desk. */
    public String method() {
        return paymentMethod == null ? "CARD" : paymentMethod;
    }


    public enum Kind {
        /** The booking is confirmed: the ticket (PDF) is attached. */
        TICKET,
        /** The booking was cancelled (by the passenger, by an admin, or with its trip). */
        CANCELLATION,
        /** The refund of the booking is done, or failed. */
        REFUND
    }

    /** The journey, for the summary in the email body. */
    public record Trip(
            @NotBlank(message = "is required") @Size(max = 200, message = "must be at most 200 characters") String train,
            @NotBlank(message = "is required") @Size(max = 200, message = "must be at most 200 characters") String fromStation,
            @NotNull(message = "is required") OffsetDateTime departure,
            @NotBlank(message = "is required") @Size(max = 200, message = "must be at most 200 characters") String toStation,
            @NotNull(message = "is required") OffsetDateTime arrival) {
    }

    /** A file attached to the email, as base64 (the ticket PDF). */
    public record Attachment(
            @NotBlank(message = "is required") @Size(max = 200, message = "must be at most 200 characters") String fileName,
            @NotBlank(message = "is required") @Size(max = 100, message = "must be at most 100 characters") String contentType,
            @NotBlank(message = "is required") @Size(max = 7_000_000, message = "is too large") String contentBase64) {
    }
}
