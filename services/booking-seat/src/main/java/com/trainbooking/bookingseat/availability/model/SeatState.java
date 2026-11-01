package com.trainbooking.bookingseat.availability.model;

/** What a seat is for one journey. "Selected" is the client's own state and never comes from the server. */
public enum SeatState {
    AVAILABLE,
    HELD,
    BOOKED
}
