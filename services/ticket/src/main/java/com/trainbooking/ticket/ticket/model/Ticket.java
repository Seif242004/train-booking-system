package com.trainbooking.ticket.ticket.model;

import java.time.OffsetDateTime;

/** A ticket without its PDF (the PDF is read on its own, because it is the large part). */
// Fields: id (identity), bookingId (id in Booking and Seat, a plain value), reference (printed on the ticket), qrToken (secret),
// status, createdAt, invalidatedAt (null while VALID). Maps one row of the table ticket.
public record Ticket(long id, long bookingId, String reference, String qrToken, TicketStatus status, OffsetDateTime createdAt, OffsetDateTime invalidatedAt) {
}
