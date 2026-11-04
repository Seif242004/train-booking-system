package com.trainbooking.bookingseat.board.model;

/** Where a trip is in its life, worked out on the server from the trip's own times and the clock (never stored). */
public enum TripState {
    UPCOMING,
    DEPARTED,
    ARRIVED,
    CANCELLED
}
