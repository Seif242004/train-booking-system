package com.trainbooking.bookingseat.outbox.service;

import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingItemView;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.booking.service.BookingQueryService;
import com.trainbooking.bookingseat.notification.NotificationPort;
import com.trainbooking.bookingseat.outbox.model.OutboxMessage;
import com.trainbooking.bookingseat.ticket.TicketPort;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Makes the PDF ticket of a confirmed booking (Ticket) and has it emailed (Notification), as Flow 4 says. Both calls are idempotent:
 * Ticket keeps one ticket per booking, and Notification stores one email per dedupe key (the id of this job), so a job that is repeated
 * after a failure in the middle never makes two tickets or sends two emails. If the booking is not confirmed any more (it was cancelled in
 * the meantime) there is nothing to send: a ticket that is already invalid must not be mailed.
 */
@Component
class TicketEmailJob implements OutboxJob {

    private static final Logger log = LoggerFactory.getLogger(TicketEmailJob.class);

    private final BookingRepository bookings;
    private final BookingQueryService queries;
    private final TicketPort tickets;
    private final NotificationPort notifications;

    TicketEmailJob(BookingRepository bookings, BookingQueryService queries, TicketPort tickets, NotificationPort notifications) {
        this.bookings = bookings;
        this.queries = queries;
        this.tickets = tickets;
        this.notifications = notifications;
    }

    @Override
    public OutboxMessage.Kind kind() {
        return OutboxMessage.Kind.TICKET_EMAIL;
    }

    @Override
    public void run(OutboxMessage message) {
        Booking booking = bookings.find(message.bookingId()).orElse(null);
        if (booking == null || booking.status() != BookingStatus.CONFIRMED) {
            log.info("Booking {} is not confirmed any more: no ticket email", message.bookingId());
            return;
        }
        BookingView view = queries.toView(booking);
        // A walk-in passenger has no account: the ticket carries their name and goes to their email, or to nobody if they gave none.
        boolean walkIn = booking.walkInName() != null;
        String passenger = walkIn ? booking.walkInName() : booking.userName() == null || booking.userName().isBlank() ? booking.userEmail() : booking.userName();
        String recipient = walkIn ? booking.walkInEmail() : booking.userEmail();

        tickets.issue(booking.id(), new TicketPort.TicketData(booking.reference(), passenger, view.trip().trainNumber(), view.trip().trainName(),
                view.trip().from().stationName(), view.trip().from().departureTime(), view.trip().to().stationName(), view.trip().to().arrivalTime(),
                view.items().stream().map(item -> new TicketPort.Line(item.type(), item.coachCode(), item.coachClass().name(), item.seatNumber(), item.qty(), item.price())).toList(),
                booking.amount(), booking.currency(), booking.paymentMethod().name()));
        byte[] pdf = tickets.pdf(booking.id());

        if (recipient == null || recipient.isBlank()) {
            log.info("Booking {} is a walk-in booking without an email: the ticket is made, no email is sent", booking.id());
            return;
        }
        notifications.send(new NotificationPort.Email("ticket:" + booking.id() + ":" + message.id(), NotificationPort.Kind.TICKET, recipient, passenger,
                booking.reference(),
                new NotificationPort.Trip(view.trip().trainNumber() + " " + view.trip().trainName(), view.trip().from().stationName(), view.trip().from().departureTime(),
                        view.trip().to().stationName(), view.trip().to().arrivalTime()),
                describe(view.items(), booking.currency()), booking.amount(), booking.currency(), null,
                new NotificationPort.Attachment("ticket-" + booking.reference() + ".pdf", "application/pdf", Base64.getEncoder().encodeToString(pdf)), booking.paymentMethod().name()));
    }

    static List<String> describe(List<BookingItemView> items, String currency) {
        return items.stream().map(item -> ("SEAT".equals(item.type()) ? "Seat " + item.seatNumber()
                : item.qty() + " standing " + (item.qty() == 1 ? "place" : "places"))
                + ", coach " + item.coachCode() + " (" + item.coachClass().name().toLowerCase(Locale.ENGLISH) + "): " + item.price().toPlainString() + " " + currency).toList();
    }
}
