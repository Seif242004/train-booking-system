package com.trainbooking.ticket.ticket.model;

/**
 * No ticket exists for the booking. Thrown by {@code TicketService}; {@code GlobalExceptionHandler} answers it as 404 ticket_not_found.
 * It is unchecked (a RuntimeException), so it passes through the service and controller without declarations, and it makes Spring roll back
 * a surrounding transaction.
 */
public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(long bookingId) {
        super("Booking " + bookingId + " has no ticket.");
    }
}
