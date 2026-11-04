package com.trainbooking.bookingseat.board.model;

/** Where a trip is in its life, worked out on the server from the trip's own times and the clock (never stored). */
// Computed by TripLifecycle from the status and the times; never stored in the database.
public enum TripState {
    UPCOMING,
    DEPARTED,
    ARRIVED,
    CANCELLED
}
