package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.payment.PaymentPort;
import com.trainbooking.bookingseat.payment.PaymentUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The expiry of holds (Flow 5, SRS 4.2, FR-H5, FR-P4). For every hold past its expiry:
 * <ol>
 *   <li><b>first</b> ask Payment to cancel the pending Stripe payment. This is a call to another service and to Stripe, so it is
 *       made here, outside any database transaction;</li>
 *   <li>only if Stripe accepted the cancellation (or there was no payment), release the seats and set EXPIRED, in one transaction;</li>
 *   <li>if Stripe says the payment already <b>succeeded</b>, confirm the booking instead: the payment wins;</li>
 *   <li>if it is still <b>processing</b>, keep the seats held and ask again at the next sweep;</li>
 *   <li>if Stripe or Payment is down, keep the seats held and ask again later, with a growing delay ({@code next_attempt_at}),
 *       so an outage is not hammered once a second.</li>
 * </ol>
 * An expired hold that has not been released yet keeps blocking its seats and shows as held, never as free. A timed-out hold is
 * never charged and never refunded: payment is blocked before any money moves.
 */
@Service
public class ExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);
    private static final int BATCH = 50;
    private static final Duration PROCESSING_RETRY = Duration.ofSeconds(1);
    private static final long MAX_BACKOFF_SECONDS = 60;

    private final BookingRepository bookings;
    private final PaymentPort payments;
    private final HoldReleaseService releases;
    private final ConfirmationService confirmations;
    private final Clock clock;

    public ExpiryService(BookingRepository bookings, PaymentPort payments, HoldReleaseService releases, ConfirmationService confirmations, Clock clock) {
        this.bookings = bookings;
        this.payments = payments;
        this.releases = releases;
        this.confirmations = confirmations;
        this.clock = clock;
    }

    /** One sweep: handles the holds that are due. Returns how many it looked at. */
    public int sweep() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Long> due = bookings.findDueHoldIds(now, BATCH);
        for (long id : due) {
            try {
                expire(id);
            } catch (RuntimeException e) {
                // One bad booking must not stop the others, nor the sweeper: it is logged and tried again later.
                log.error("Expiring booking {} failed", id, e);
                try {
                    retryLater(id, bookings.find(id).map(Booking::attempts).orElse(0) + 1);
                } catch (RuntimeException secondFailure) {
                    log.error("Booking {} could not even be scheduled for another try (is the database down?)", id, secondFailure);
                }
            }
        }
        return due.size();
    }

    /** Handles one hold that is past its expiry. */
    public void expire(long bookingId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The call to Payment and Stripe below must never run inside an open database transaction (CLAUDE.md).
            throw new IllegalStateException("A hold must be expired outside any transaction: it calls Payment and Stripe first");
        }
        Booking booking = bookings.find(bookingId).orElse(null);
        if (booking == null || booking.status() != BookingStatus.PENDING_PAYMENT) {
            return;
        }
        PaymentPort.CancelOutcome outcome;
        try {
            outcome = payments.cancelPending(bookingId); // before anything is released, and not inside a transaction
        } catch (PaymentUnavailableException e) {
            log.warn("Payment of booking {} could not be cancelled yet: {}", bookingId, e.getMessage());
            retryLater(bookingId, booking.attempts() + 1);
            return;
        }
        switch (outcome) {
            case NONE, CANCELLED -> releases.release(bookingId, HoldReleaseService.Reason.EXPIRED, true, "system");
            case SUCCEEDED -> {
                // The payment won the race with the timeout.
                ConfirmationService.Result result = confirmations.confirm(bookingId);
                if (result == ConfirmationService.Result.NOT_CONFIRMABLE) {
                    // Money was taken for a hold that is already gone. Payment refunds such a payment when it learns of it.
                    log.error("Booking {} was paid but its hold is already over: the payment must be refunded", bookingId);
                }
            }
            case PROCESSING -> bookings.scheduleRetry(bookingId, at(clock.instant().plus(PROCESSING_RETRY)), booking.attempts());
        }
    }

    /** Exponential backoff: 2, 4, 8 ... seconds, at most a minute. */
    private void retryLater(long bookingId, int attempts) {
        if (attempts >= 5) {
            log.error("Booking {} could not be expired after {} attempts: its seats stay held until this is looked at", bookingId, attempts);
        }
        long seconds = Math.min(1L << Math.min(attempts, 10), MAX_BACKOFF_SECONDS);
        bookings.scheduleRetry(bookingId, at(clock.instant().plusSeconds(seconds)), attempts);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
