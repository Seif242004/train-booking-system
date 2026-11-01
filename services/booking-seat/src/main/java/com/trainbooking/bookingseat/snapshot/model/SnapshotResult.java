package com.trainbooking.bookingseat.snapshot.model;

/** What a snapshot push did: whether the trip is new, and how many rows of each kind the copy now has. */
public record SnapshotResult(long tripId, boolean created, int stops, int coaches, int seats, int fares, int standingCounters) {
}
