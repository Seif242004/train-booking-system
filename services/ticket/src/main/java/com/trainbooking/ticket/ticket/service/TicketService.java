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
// {@code @Service} marks the business-logic bean (a {@code @Component} with a name for its role). The logic of this service is small:
// issue once, read, invalidate once. All transaction boundaries are here.
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    /** The ticket, and whether this call made it. */
    // The return type of issue: lets the controller choose between 201 (created) and 200 (existing).
    public record Issued(Ticket ticket, boolean created) {
    }

    private final TicketRepository tickets;
    private final QrTokens tokens;
    private final TicketPdfRenderer renderer;
    private final AuditTrail audit;
    private final Clock clock;

    // All collaborators arrive through the constructor. The Clock is the UTC bean from TicketConfig (tests replace it).
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
    // {@code @Transactional} runs the method in one database transaction: commit if it returns, rollback if it throws a RuntimeException.
    // The ticket row and the audit_log row of issue() are therefore saved together or not at all.
    @Transactional
    // Steps: 1. Look for a ticket of this booking; if there is one, return it unchanged (idempotent: the same QR and the same PDF, also
    // if it
    // is INVALIDATED, so a repeated call never revives a cancelled ticket). 2. Make a new random QR token. 3. Draw the PDF with that token.
    // 4. Insert the row. 5. Write the audit row. 6. If two calls race, both pass step 1; the unique key lets only one INSERT win and the
    // other
    // gets DuplicateKeyException, which is answered with the winner's ticket. Booking and Seat repeats this call until it succeeds
    // (its outbox), which is why the idempotence matters. The PDF is drawn before the INSERT but is not a database action.
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
        // The Oracle transaction of the loser stays usable after ORA-00001 on a plain INSERT, so the re-read below works in the same
        // transaction.
        } catch (DuplicateKeyException e) {
            // Two requests for the same booking at the same moment: the other one won. Answer with its ticket.
            log.info("Ticket for booking {} was made by a concurrent request", bookingId);
            return new Issued(tickets.findByBooking(bookingId).orElseThrow(), false);
        }
    }

    // {@code readOnly = true} tells Spring and the driver that the method only reads, which allows optimizations and prevents accidental
    // writes.
    @Transactional(readOnly = true)
    public Ticket get(long bookingId) {
        return tickets.findByBooking(bookingId).orElseThrow(() -> new TicketNotFoundException(bookingId));
    }

    @Transactional(readOnly = true)
    public byte[] pdf(long bookingId) {
        return tickets.findPdf(bookingId).orElseThrow(() -> new TicketNotFoundException(bookingId));
    }

    /** The QR code of the ticket as a PNG for the ticket page: the token that is in the PDF, drawn by the same method. */
    @Transactional(readOnly = true)
    // The QR picture is drawn on demand from the stored token (the picture itself is not stored), at the size of the ticket page.
    public byte[] qrPng(long bookingId) {
        return QrImages.png(get(bookingId).qrToken(), QrImages.PAGE_SIZE);
    }

    /** The booking was cancelled: the QR token stops being valid. Idempotent. */
    @Transactional
    // Steps: 1. Lock the row (FOR UPDATE) so concurrent invalidations queue up. 2. If it is VALID: set INVALIDATED with the time and write
    // the audit row. 3. If it is already INVALIDATED: return it as it is (idempotent). A missing ticket is 404; Booking and Seat retries
    // until it exists (the ticket and its cancellation are both queued jobs of one booking, run in order).
    public Ticket invalidate(long bookingId) {
        Ticket ticket = tickets.findByBookingForUpdate(bookingId).orElseThrow(() -> new TicketNotFoundException(bookingId));
        if (ticket.status() == TicketStatus.VALID) {
            tickets.invalidate(ticket.id(), now());
            audit.record("booking-service", "ticket", ticket.id(), "INVALIDATE", Map.of("bookingId", bookingId));
            return tickets.findByBooking(bookingId).orElseThrow();
        }
        return ticket;
    }

    // Always UTC from the injected clock.
    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
