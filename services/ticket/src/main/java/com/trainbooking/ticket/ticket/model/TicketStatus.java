package com.trainbooking.ticket.ticket.model;

/**
 * State of a ticket (table column {@code status}, a CHECK allows only these two). VALID from the moment it is made; INVALIDATED when its
 * booking is cancelled. There is no way back: a repeated "issue" request never makes an invalidated ticket valid again.
 */
public enum TicketStatus {
    VALID, INVALIDATED
}
