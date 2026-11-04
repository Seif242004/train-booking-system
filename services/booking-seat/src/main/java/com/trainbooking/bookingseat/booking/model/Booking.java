package com.trainbooking.bookingseat.booking.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One row of the booking table. */
// One row of the booking table (V2 + V4 + V8), in the order of BookingRepository.COLUMNS. Money is BigDecimal (never double);
// times are OffsetDateTime (an instant with an offset). nextAttemptAt/attempts are the sweeper's retry state; paymentStarted is the
// one-way flag "the user opened the payment page" (never cleared); the issue* and walkIn* fields describe counter and complimentary
// bookings.
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
        boolean paymentStarted,
        PaymentMethod paymentMethod,
        BigDecimal chargedAmount,
        String issuedBy,
        OffsetDateTime issuedAt,
        String issueNote,
        String walkInName,
        String walkInPhone,
        String walkInEmail,
        String counterRefundedBy,
        OffsetDateTime counterRefundedAt) {
}
