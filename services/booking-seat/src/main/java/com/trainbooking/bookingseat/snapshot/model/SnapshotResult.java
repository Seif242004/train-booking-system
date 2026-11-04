package com.trainbooking.bookingseat.snapshot.model;

/** What a snapshot push did: whether the trip is new, and how many rows of each kind the copy now has. */
// The answer to a snapshot push: created = true if Booking had no copy before; the counts are the rows the copy has afterwards.
public record SnapshotResult(long tripId, boolean created, int stops, int coaches, int seats, int fares, int standingCounters) {
}
