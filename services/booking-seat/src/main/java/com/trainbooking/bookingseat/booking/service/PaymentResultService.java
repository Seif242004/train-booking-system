package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the Payment service tells Booking (CLAUDE.md: Payment to Booking, payment result and refund status). Both calls are repeated
 * by Payment until Booking answers, so both are idempotent.
 */
@Service
public class PaymentResultService {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultService.class);

    /** What Payment must do with the money. */
    public enum Result {
        /** The booking is confirmed (now, or before). */
        CONFIRMED,
        /** The booking cannot take this payment: its hold is gone, or the amount differs. Payment refunds it. */
        REFUND_REQUIRED
    }

    private final BookingRepository bookings;
    private final ConfirmationService confirmations;
    private final HoldReleaseService releases;
    private final AuditTrail audit;

    public PaymentResultService(BookingRepository bookings, ConfirmationService confirmations, HoldReleaseService releases, AuditTrail audit) {
        this.bookings = bookings;
        this.confirmations = confirmations;
        this.releases = releases;
        this.audit = audit;
    }

    /**
     * A payment succeeded at Stripe (SRS 7.1, step 4): the money is taken. If the booking is still a hold for exactly this amount it is
     * confirmed, even if its five minutes have just run out (the payment wins). Otherwise the money cannot become a booking.
     */
    public Result paymentSucceeded(long bookingId, BigDecimal paidAmount) {
        Booking booking = bookings.find(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (booking.status() == BookingStatus.PENDING_PAYMENT && paidAmount.compareTo(booking.amount()) != 0) {
            // Backstop, not expected: a change of the hold always cancels the open payment first. Never confirm a booking for the wrong
            // amount: end the hold (so the sweeper cannot confirm it later either) and have the money refunded.
            log.error("Booking {} was paid {} but costs {}: the hold is ended and the payment refunded", bookingId, paidAmount, booking.amount());
            releases.release(bookingId, HoldReleaseService.Reason.EXPIRED, false, "system", true);
            audit.record("system", "booking", bookingId, "PAYMENT_MISMATCH", Map.of("paid", paidAmount, "amount", booking.amount()));
            return Result.REFUND_REQUIRED;
        }
        return switch (confirmations.confirm(bookingId)) {
            case CONFIRMED, ALREADY_CONFIRMED -> Result.CONFIRMED;
            case NOT_CONFIRMABLE -> {
                audit.record("system", "booking", bookingId, "PAYMENT_AFTER_HOLD", Map.of("paid", paidAmount, "status", booking.status().name()));
                yield Result.REFUND_REQUIRED;
            }
            case NOT_FOUND -> throw new BookingNotFoundException(bookingId);
        };
    }

    /** Mirrors the refund status of Payment in the booking. */
    @Transactional
    public void refundStatus(long bookingId, RefundStatus status) {
        Booking booking = bookings.findForUpdate(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (booking.refundStatus() == status) {
            return;
        }
        bookings.setRefundStatus(bookingId, status);
        audit.record("payment", "booking", bookingId, "REFUND_STATUS", Map.of("from", booking.refundStatus().name(), "to", status.name()));
    }
}
