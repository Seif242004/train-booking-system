package com.trainbooking.bookingseat.booking.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One row of the booking table. */
public record Booking(
        long id,
        String reference,
        String userId,
        String userEmail,
        String userName,
        long tripId,
        int fromStop,
        int toStop,
        BookingStatus status,
        RefundStatus refundStatus,
        BigDecimal amount,
        String currency,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt,
        String cancelledBy,
        String cancelledByRole,
        String cancelReason,
        OffsetDateTime nextAttemptAt,
        int attempts,
        boolean paymentStarted) {
}
