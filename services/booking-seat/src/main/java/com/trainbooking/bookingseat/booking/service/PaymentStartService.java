package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.HoldExpiredException;
import com.trainbooking.bookingseat.booking.model.PaymentStart;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.payment.PaymentPort;
import com.trainbooking.bookingseat.payment.PaymentRejectedException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Continue to payment" (Flow 4, SRS 7.1): checks that the hold is still active, then asks Payment for a payment of the amount the
 * server worked out.
 *
 * <p><b>Order matters.</b> In one short transaction (under the booking lock, the same order as every hold change) the hold is
 * re-checked, the amount is read and {@code payment_started} is set. Only then is Payment called, outside any transaction. A hold
 * that changes after that sees the flag and cancels the payment first; a change that read the booking before the flag was set is
 * detected under its own lock and retried (see {@code PaymentStartedMeanwhileException}). So an open payment can never be missed.
 */
@Service
public class PaymentStartService {

    private final BookingRepository bookings;
    private final BookingLocks locks;
    private final PaymentPort payments;
    private final AuditTrail audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentStartService(BookingRepository bookings, BookingLocks locks, PaymentPort payments, AuditTrail audit, TransactionTemplate tx, Clock clock) {
        this.bookings = bookings;
        this.locks = locks;
        this.payments = payments;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public PaymentStart start(String userId, long bookingId) {
        // The owner check first, and without a lock: someone else's booking is "not found", whatever its state.
        bookings.find(bookingId).filter(b -> b.userId().equals(userId)).orElseThrow(() -> new BookingNotFoundException(bookingId));

        Booking hold = tx.execute(status -> {
            Booking locked = locks.lock(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
            requireActiveHold(locked);
            if (!locked.paymentStarted()) {
                bookings.setPaymentStarted(bookingId, true);
                audit.record(userId, "booking", bookingId, "PAYMENT_PAGE", Map.of("amount", locked.amount()));
            }
            return locked;
        });

        PaymentPort.PaymentSession session = createOrReplace(hold);
        return new PaymentStart(session.clientSecret(), session.publishableKey(), hold.amount(), hold.currency(), hold.expiresAt(),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private PaymentPort.PaymentSession createOrReplace(Booking hold) {
        try {
            return payments.createPayment(hold.id(), hold.amount(), hold.currency());
        } catch (PaymentRejectedException e) {
            if (!"payment_outdated".equals(e.code())) {
                throw e;
            }
            // Payment still has an open payment for an earlier amount (a change that slipped through): cancel it and make the right one.
            payments.invalidate(hold.id());
            return payments.createPayment(hold.id(), hold.amount(), hold.currency());
        }
    }

    private void requireActiveHold(Booking booking) {
        if (booking.status() == BookingStatus.CONFIRMED) {
            throw new BookingConflictException("already_paid", "This booking is already paid.");
        }
        if (booking.status() == BookingStatus.EXPIRED) {
            throw new HoldExpiredException();
        }
        if (booking.status() != BookingStatus.PENDING_PAYMENT) {
            throw new BookingConflictException("not_a_hold", "This booking is not a hold any more.");
        }
        // Payment requests for an expired hold are refused (CLAUDE.md), even if the sweeper has not released it yet.
        if (!booking.expiresAt().toInstant().isAfter(clock.instant())) {
            throw new HoldExpiredException();
        }
    }
}
