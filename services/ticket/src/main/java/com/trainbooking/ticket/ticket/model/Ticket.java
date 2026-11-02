package com.trainbooking.ticket.ticket.model;

import java.time.OffsetDateTime;

/** A ticket without its PDF (the PDF is read on its own, because it is the large part). */
public record Ticket(long id, long bookingId, String reference, String qrToken, TicketStatus status, OffsetDateTime createdAt, OffsetDateTime invalidatedAt) {
}
