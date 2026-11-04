package com.trainbooking.bookingseat.availability.model;

/** What a seat is for one journey. "Selected" is the client's own state and never comes from the server. */
// AVAILABLE can be picked, HELD is in somebody's unpaid hold (also an expired hold that the sweeper has not released yet), BOOKED is
// confirmed.
public enum SeatState {
    AVAILABLE,
    HELD,
    BOOKED
}
