package com.trainbooking.ticket.ticket.service;

import com.trainbooking.ticket.audit.AuditTrail;
import com.trainbooking.ticket.ticket.model.Ticket;
import com.trainbooking.ticket.ticket.model.TicketNotFoundException;
import com.trainbooking.ticket.ticket.model.TicketRequest;
import com.trainbooking.ticket.ticket.model.TicketStatus;
import com.trainbooking.ticket.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The PDF ticket of a booking: made once when the booking is confirmed, kept as the record, and invalidated (its QR token no longer
 * valid) when the booking is cancelled. Both calls are repeated by Booking and Seat until they succeed, so both are idempotent.
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    /** The ticket, and whether this call made it. */
    public record Issued(Ticket ticket, boolean created) {
    }

    private final TicketRepository tickets;
    private final QrTokens tokens;
    private final TicketPdfRenderer renderer;
    private final AuditTrail audit;
    private final Clock clock;

    public TicketService(TicketRepository tickets, QrTokens tokens, TicketPdfRenderer renderer, AuditTrail audit, Clock clock) {
        this.tickets = tickets;
        this.tokens = tokens;
        this.renderer = renderer;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Makes the ticket of a booking, or returns the one that exists (the same ticket, the same QR code, even if it was invalidated
     * since: a repeated request never brings a cancelled ticket back to life).
     */
    @Transactional
    public Issued issue(long bookingId, TicketRequest request) {
        Ticket existing = tickets.findByBooking(bookingId).orElse(null);
        if (existing != null) {
            return new Issued(existing, false);
        }
        String token = tokens.issue(request.reference());
        byte[] pdf = renderer.render(request, token);
        try {
            Ticket created = tickets.insert(bookingId, request.reference(), token, pdf, now());
            audit.record("booking-service", "ticket", created.id(), "CREATE", Map.of("bookingId", bookingId, "reference", request.reference()));
            return new Issued(created, true);
        } catch (DuplicateKeyException e) {
            // Two requests for the same booking at the same moment: the other one won. Answer with its ticket.
            log.info("Ticket for booking {} was made by a concurrent request", bookingId);
            return new Issued(tickets.findByBooking(bookingId).orElseThrow(), false);
        }
    }

    @Transactional(readOnly = true)
    public Ticket get(long bookingId) {
        return tickets.findByBooking(bookingId).orElseThrow(() -> new TicketNotFoundException(bookingId));
    }

    @Transactional(readOnly = true)
    public byte[] pdf(long bookingId) {
        return tickets.findPdf(bookingId).orElseThrow(() -> new TicketNotFoundException(bookingId));
    }

    /** The booking was cancelled: the QR token stops being valid. Idempotent. */
    @Transactional
    public Ticket invalidate(long bookingId) {
        Ticket ticket = tickets.findByBookingForUpdate(bookingId).orElseThrow(() -> new TicketNotFoundException(bookingId));
        if (ticket.status() == TicketStatus.VALID) {
            tickets.invalidate(ticket.id(), now());
            audit.record("booking-service", "ticket", ticket.id(), "INVALIDATE", Map.of("bookingId", bookingId));
            return tickets.findByBooking(bookingId).orElseThrow();
        }
        return ticket;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
