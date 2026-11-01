package com.trainbooking.payment.outbox.service;

import com.trainbooking.payment.outbox.model.OutboxMessage;
import com.trainbooking.payment.outbox.repository.OutboxRepository;
import com.trainbooking.payment.refund.model.NoPaymentToRefundException;
import com.trainbooking.payment.refund.service.RefundService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers the outbox to Booking and Seat (CLAUDE.md, "Async calls without a broker"). A message stays until Booking accepted it, so a
 * Booking that is down for an hour still learns of every payment afterwards. Failures back off (2, 4 ... 60 seconds) and are logged;
 * the news of one booking goes out in order. HTTP calls are never made inside a database transaction.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outbox;
    private final BookingNotifier booking;
    private final RefundService refunds;
    private final Clock clock;

    public OutboxDispatcher(OutboxRepository outbox, BookingNotifier booking, RefundService refunds, Clock clock) {
        this.outbox = outbox;
        this.booking = booking;
        this.refunds = refunds;
        this.clock = clock;
    }

    /** @return how many messages were delivered */
    public int dispatchDue() {
        int delivered = 0;
        for (OutboxMessage message : outbox.findDue(now(), 20)) {
            try {
                deliver(message);
                outbox.markSent(message.id());
                delivered++;
            } catch (BookingNotifier.BookingRejectedException e) {
                log.error("Booking will not accept outbox message {} ({} for booking {}): {}", message.id(), message.kind(), message.bookingId(), e.getMessage());
                if (message.kind() == OutboxMessage.Kind.PAYMENT_RESULT) {
                    // Money was taken and the booking cannot take it (it does not know the booking, or the message was refused): never leave
                    // a payment with neither a booking nor a refund. The refund request is idempotent.
                    try {
                        refunds.request(message.bookingId(), "Booking did not accept the payment.");
                    } catch (RuntimeException refundFailure) {
                        log.error("Refund for the refused payment of booking {} could not be requested: {}", message.bookingId(), refundFailure.getMessage());
                        outbox.scheduleRetry(message.id(), now().plusSeconds(30), "refund not requested: " + refundFailure.getMessage());
                        continue;
                    }
                }
                outbox.markFailed(message.id(), e.getMessage());
            } catch (RuntimeException e) {
                OffsetDateTime next = now().plusSeconds(Math.min(60, 1L << Math.min(message.attempts() + 1, 6)));
                log.warn("Outbox message {} ({} for booking {}) not delivered, next try {}: {}", message.id(), message.kind(), message.bookingId(), next, e.getMessage());
                outbox.scheduleRetry(message.id(), next, e.getMessage());
            }
        }
        return delivered;
    }

    private void deliver(OutboxMessage message) {
        switch (message.kind()) {
            case PAYMENT_RESULT -> {
                BookingNotifier.PaymentAnswer answer = booking.paymentResult(message.bookingId(), message.payload());
                if (answer == BookingNotifier.PaymentAnswer.REFUND_REQUIRED) {
                    // The money cannot become a booking. Refund it (idempotent, so a retry of this message is safe).
                    try {
                        refunds.request(message.bookingId(), "The booking could not take this payment.");
                    } catch (NoPaymentToRefundException e) {
                        log.error("Booking {} asked for a refund but no payment was taken", message.bookingId());
                    }
                }
            }
            case REFUND_STATUS -> booking.refundStatus(message.bookingId(), message.payload());
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
