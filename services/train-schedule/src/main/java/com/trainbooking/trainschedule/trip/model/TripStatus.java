package com.trainbooking.trainschedule.trip.model;

/**
 * A trip is {@code ACTIVE} until the whole trip is cancelled (Flow 8, slice 12), which sets {@code CANCELLED}. Cancelled
 * trips are not returned by trip search.
 */
public enum TripStatus {
    ACTIVE,
    CANCELLED
}
