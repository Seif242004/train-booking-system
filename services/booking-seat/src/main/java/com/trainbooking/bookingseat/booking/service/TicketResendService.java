package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.outbox.service.OutboxWriter;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Resend ticket" (SRS FR-T4, {@code POST /bookings/{id}/resend-ticket}): the passenger asks for the ticket email again. It queues the same job
 * as a confirmation does; the Ticket service returns the same ticket and the email goes out again. Asking while the first is still waiting
 * adds nothing, so the button cannot be used to flood anyone's inbox.
 */
@Service
public class TicketResendService {

    private final BookingRepository bookings;
    private final OutboxWriter outbox;
    private final AuditTrail audit;

    public TicketResendService(BookingRepository bookings, OutboxWriter outbox, AuditTrail audit) {
        this.bookings = bookings;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** @return true if an email was queued, false if one is already waiting */
    @Transactional
    public boolean resend(String userId, long bookingId) {
        // Owner check: someone else's booking is "not found", whatever its state.
        Booking booking = bookings.findForUpdate(bookingId).filter(b -> b.userId().equals(userId)).orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (booking.status() != BookingStatus.CONFIRMED) {
            throw new BookingConflictException("not_confirmed", "Only a confirmed booking has a ticket.");
        }
        boolean queued = outbox.queueTicketEmailUnlessPending(bookingId);
        if (queued) {
            audit.record(userId, "booking", bookingId, "RESEND_TICKET", Map.of("reference", booking.reference()));
        }
        return queued;
    }
}
