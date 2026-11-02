package com.trainbooking.bookingseat.outbox.service;

import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.booking.service.BookingQueryService;
import com.trainbooking.bookingseat.booking.service.PaymentResultService;
import com.trainbooking.bookingseat.notification.NotificationPort;
import com.trainbooking.bookingseat.outbox.model.OutboxMessage;
import com.trainbooking.bookingseat.payment.RefundPort;
import com.trainbooking.bookingseat.ticket.TicketPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The follow-up work of a cancellation (Flow 6, steps 3, 4 and 6), queued by {@code CancellationService} in the cancelling transaction and
 * done here with retries. Each job is idempotent, because the dispatcher repeats a job until it succeeds. None of them can change whether
 * the booking is cancelled: that was decided, and committed, before they ran.
 */
public final class CancellationJobs {

    private CancellationJobs() {
    }

    /** The QR code of the ticket stops working. */
    @Component
    static class TicketInvalidateJob implements OutboxJob {

        private final TicketPort tickets;

        TicketInvalidateJob(TicketPort tickets) {
            this.tickets = tickets;
        }

        @Override
        public OutboxMessage.Kind kind() {
            return OutboxMessage.Kind.TICKET_INVALIDATE;
        }

        @Override
        public void run(OutboxMessage message) {
            tickets.invalidate(message.bookingId()); // a booking that never got a ticket is not an error
        }
    }

    /**
     * Asks Payment for the full refund. Payment keeps one refund per booking, so repeating the request returns the same refund. If the booking
     * has no payment that was taken there is nothing to refund: the refund is flagged REFUND_FAILED so an admin looks at it.
     */
    @Component
    static class RefundRequestJob implements OutboxJob {

        private static final Logger log = LoggerFactory.getLogger(RefundRequestJob.class);

        /** About twenty minutes of retries (the backoff grows to five minutes). */
        static final int FLAG_AFTER_ATTEMPTS = 8;

        private final BookingRepository bookings;
        private final RefundPort refunds;
        private final PaymentResultService results;

        RefundRequestJob(BookingRepository bookings, RefundPort refunds, PaymentResultService results) {
            this.bookings = bookings;
            this.refunds = refunds;
            this.results = results;
        }

        @Override
        public OutboxMessage.Kind kind() {
            return OutboxMessage.Kind.REFUND_REQUEST;
        }

        /** The passenger's money is owed: never give up. */
        @Override
        public boolean retryForever() {
            return true;
        }

        /**
         * A refund request that keeps failing must leave a trace on the booking, not only in a log: after a few attempts the refund is flagged
         * REFUND_FAILED for the admin (Flow 7, support) while the retries go on. If the refund then goes through, Payment's report changes it again.
         */
        @Override
        public void onFailure(OutboxMessage message, int attempt, RuntimeException failure) {
            if (attempt == FLAG_AFTER_ATTEMPTS) {
                log.error("The refund of booking {} could not be requested after {} attempts: flagged REFUND_FAILED for an admin, retrying", message.bookingId(), attempt);
                results.refundStatus(message.bookingId(), RefundStatus.REFUND_FAILED);
            }
        }

        @Override
        public void run(OutboxMessage message) {
            Booking booking = bookings.find(message.bookingId()).orElse(null);
            if (booking == null || booking.status() != BookingStatus.CANCELLED) {
                return;
            }
            String reason = booking.cancelReason() != null ? booking.cancelReason() : "Cancelled by the passenger.";
            if (refunds.requestRefund(booking.id(), reason) == RefundPort.Result.NO_PAYMENT) {
                log.error("Booking {} was cancelled but Payment has no payment to refund: flagged REFUND_FAILED for an admin", booking.id());
                results.refundStatus(booking.id(), RefundStatus.REFUND_FAILED);
            }
        }
    }

    /** Tells the passenger the booking is cancelled, and that the whole amount is being refunded. */
    @Component
    static class CancellationEmailJob implements OutboxJob {

        private final BookingRepository bookings;
        private final BookingQueryService queries;
        private final NotificationPort notifications;

        CancellationEmailJob(BookingRepository bookings, BookingQueryService queries, NotificationPort notifications) {
            this.bookings = bookings;
            this.queries = queries;
            this.notifications = notifications;
        }

        @Override
        public OutboxMessage.Kind kind() {
            return OutboxMessage.Kind.CANCELLATION_EMAIL;
        }

        @Override
        public void run(OutboxMessage message) {
            Booking booking = bookings.find(message.bookingId()).orElse(null);
            if (booking == null) {
                return;
            }
            String detail;
            if ("ADMIN".equals(booking.cancelledByRole())) {
                detail = "Your booking was cancelled by our team." + (booking.cancelReason() == null || booking.cancelReason().isBlank() ? "" : " Reason: " + booking.cancelReason().trim());
            } else {
                detail = "You cancelled this booking.";
            }
            BookingView view = queries.toView(booking);
            notifications.send(new NotificationPort.Email("cancel:" + booking.id(), NotificationPort.Kind.CANCELLATION, booking.userEmail(), booking.userName(),
                    booking.reference(), trip(view), TicketEmailJob.describe(view.items(), booking.currency()), booking.amount(), booking.currency(), detail, null));
        }
    }

    /** Tells the passenger the refund is done (queued when Payment reports REFUNDED). */
    @Component
    static class RefundEmailJob implements OutboxJob {

        private final BookingRepository bookings;
        private final BookingQueryService queries;
        private final NotificationPort notifications;

        RefundEmailJob(BookingRepository bookings, BookingQueryService queries, NotificationPort notifications) {
            this.bookings = bookings;
            this.queries = queries;
            this.notifications = notifications;
        }

        @Override
        public OutboxMessage.Kind kind() {
            return OutboxMessage.Kind.REFUND_EMAIL;
        }

        @Override
        public void run(OutboxMessage message) {
            Booking booking = bookings.find(message.bookingId()).orElse(null);
            if (booking == null) {
                return;
            }
            notifications.send(new NotificationPort.Email("refund:" + booking.id(), NotificationPort.Kind.REFUND, booking.userEmail(), booking.userName(),
                    booking.reference(), trip(queries.toView(booking)), java.util.List.of(), booking.amount(), booking.currency(), null, null));
        }
    }

    private static NotificationPort.Trip trip(BookingView view) {
        return new NotificationPort.Trip(view.trip().trainNumber() + " " + view.trip().trainName(), view.trip().from().stationName(), view.trip().from().departureTime(),
                view.trip().to().stationName(), view.trip().to().arrivalTime());
    }
}
