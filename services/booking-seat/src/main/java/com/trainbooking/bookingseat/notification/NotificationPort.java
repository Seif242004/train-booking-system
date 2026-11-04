package com.trainbooking.bookingseat.notification;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What Booking and Seat needs from the Notification service. The service stores the request and sends the email later, with retries, so a
 * successful call only means "accepted". Asking again with the same {@code dedupeKey} stores no second email. Throws
 * {@link com.trainbooking.bookingseat.common.RemoteServiceException} when the answer is not known.
 */
public interface NotificationPort {

    enum Kind { TICKET, CANCELLATION, REFUND }

    record Trip(String train, String fromStation, OffsetDateTime departure, String toStation, OffsetDateTime arrival) {
    }

    record Attachment(String fileName, String contentType, String contentBase64) {
    }

    record Email(String dedupeKey, Kind kind, String to, String toName, String reference, Trip trip, List<String> lines, BigDecimal amount,
                 String currency, String detail, Attachment attachment, String paymentMethod) {

        /** A card payment (the original shape). */
        public Email(String dedupeKey, Kind kind, String to, String toName, String reference, Trip trip, List<String> lines, BigDecimal amount,
                     String currency, String detail, Attachment attachment) {
            this(dedupeKey, kind, to, toName, reference, trip, lines, amount, currency, detail, attachment, "CARD");
        }
    }

    void send(Email email);
}
