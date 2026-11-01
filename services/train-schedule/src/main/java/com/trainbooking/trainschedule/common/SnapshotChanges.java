package com.trainbooking.trainschedule.common;

/**
 * Tells the system that something that is part of the snapshots of trips was edited (CLAUDE.md, "Data copy"): Booking and
 * Seat keeps its own copy of the trips, so the copies of the affected trips must be pushed again. The services that edit
 * trains, coaches, seats, standing capacity, fares, routes and stations call it inside their own transaction.
 *
 * <p>Only trips that have not left yet and are still active are pushed again: a trip that already left keeps the copy it
 * had. The push happens as the last step before the commit, so if Booking and Seat refuses it (the trip has holds or
 * bookings that the edit would conflict with, FR-A9, or fares that are frozen), the whole edit is rolled back.
 */
public interface SnapshotChanges {

    /** A train, one of its coaches, seats or standing capacities changed. */
    void trainChanged(long trainId, String adminId);

    /** The fares or the name of a route changed. */
    void routeChanged(long routeId, String adminId);

    /** A station (its name appears in the stops of the snapshots) changed. */
    void stationChanged(long stationId, String adminId);
}
