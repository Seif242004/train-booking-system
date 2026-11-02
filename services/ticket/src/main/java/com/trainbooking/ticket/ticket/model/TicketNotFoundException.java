package com.trainbooking.ticket.ticket.model;

public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(long bookingId) {
        super("Booking " + bookingId + " has no ticket.");
    }
}
