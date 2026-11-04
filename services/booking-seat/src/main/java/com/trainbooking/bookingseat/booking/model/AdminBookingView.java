package com.trainbooking.bookingseat.booking.model;

import java.time.OffsetDateTime;

/**
  * A booking as support sees it (Flow 7): everything the passenger sees plus who the passenger is, whether the money was taken, who
  * cancelled
 * it and why. {@code paymentStatus} is PAID once the booking was confirmed (the payment succeeded) and NOT_PAID before; the refund is
  * {@code booking.refundStatus}. {@code cancellable} is what support may still do: a confirmed booking whose train has not left the
  * boarding stop.
 */
// The Gateway forwards this record to the admin support page. The second constructor below keeps the old 11-argument form working
// (it fills "issue" with null); a record can have extra constructors as long as they call the canonical one with this(...).
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
