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
// A holder class (it cannot be instantiated) for the four jobs that follow a cancellation. Each nested class is a Spring bean found by the
// dispatcher through the OutboxJob interface.
public final class CancellationJobs {

    private CancellationJobs() {
    }

    /** The QR code of the ticket stops working. */
    @Component
    // @Component: a bean. Idempotent: invalidating twice, or a booking without a ticket, is not an error.
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
      * Asks Payment for the full refund. Payment keeps one refund per booking, so repeating the request returns the same refund. If the
      * booking
     * has no payment that was taken there is nothing to refund: the refund is flagged REFUND_FAILED so an admin looks at it.
     */
    @Component
    // Never gives up (retryForever): the passenger's money is owed. After 8 failed attempts the booking is flagged REFUND_FAILED so an
    // admin
    // sees it in support, while the retries go on. If Payment says there is no payment to refund, it is flagged at once.
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
          * A refund request that keeps failing must leave a trace on the booking, not only in a log: after a few attempts the refund is
          * flagged
          * REFUND_FAILED for the admin (Flow 7, support) while the retries go on. If the refund then goes through, Payment's report
          * changes it again.
         */
        @Override
        // The hook of OutboxJob: called by the dispatcher after every failed attempt, with the attempt number starting at 1.
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
    // Builds the cancellation e-mail. Admin cancellation says so and gives the reason. A walk-in passenger gets the mail at the address
    // they
    // left at the counter (if any); the dedupe key "cancel:<bookingId>" makes Notification store it once.
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
            boolean walkIn = booking.walkInName() != null;
            String recipient = walkIn ? booking.walkInEmail() : booking.userEmail();
            if (recipient == null || recipient.isBlank()) {
                return; // a walk-in passenger who gave no email
            }
            notifications.send(new NotificationPort.Email("cancel:" + booking.id(), NotificationPort.Kind.CANCELLATION, recipient, walkIn ? booking.walkInName() : booking.userName(),
                    booking.reference(), trip(view), TicketEmailJob.describe(view.items(), booking.currency()), booking.amount(), booking.currency(), detail, null,
                    booking.paymentMethod().name()));
        }
    }

    /** Tells the passenger the refund is done (queued when Payment reports REFUNDED). */
    @Component
    // Queued when Payment reports REFUNDED. Dedupe key "refund:<bookingId>".
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

    // Shared helper: the train, stations and times for the e-mail.
    private static NotificationPort.Trip trip(BookingView view) {
        return new NotificationPort.Trip(view.trip().trainNumber() + " " + view.trip().trainName(), view.trip().from().stationName(), view.trip().from().departureTime(),
                view.trip().to().stationName(), view.trip().to().arrivalTime());
    }
}
