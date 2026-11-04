package com.trainbooking.bookingseat.booking.model;

import java.time.OffsetDateTime;

/**
 * A booking as support sees it (Flow 7): everything the passenger sees plus who the passenger is, whether the money was taken, who cancelled
 * it and why. {@code paymentStatus} is PAID once the booking was confirmed (the payment succeeded) and NOT_PAID before; the refund is
 * {@code booking.refundStatus}. {@code cancellable} is what support may still do: a confirmed booking whose train has not left the boarding stop.
 */
public record AdminBookingView(
        BookingView booking,
        String userId,
        String userEmail,
        String userName,
        String paymentStatus,
        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt,
        String cancelledBy,
        String cancelledByRole,
        String cancelReason,
        boolean cancellable,
        Issue issue) {

    /** How an admin-issued booking was issued (null for a card booking): the audit that support sees. */
    public record Issue(PaymentMethod method, java.math.BigDecimal chargedAmount, String issuedBy, OffsetDateTime issuedAt, String note,
                        String walkInName, String walkInPhone, String walkInEmail, String counterRefundedBy, OffsetDateTime counterRefundedAt) {
    }

    public AdminBookingView(BookingView booking, String userId, String userEmail, String userName, String paymentStatus, OffsetDateTime confirmedAt,
                            OffsetDateTime cancelledAt, String cancelledBy, String cancelledByRole, String cancelReason, boolean cancellable) {
        this(booking, userId, userEmail, userName, paymentStatus, confirmedAt, cancelledAt, cancelledBy, cancelledByRole, cancelReason, cancellable, null);
    }

}
