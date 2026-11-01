package com.trainbooking.bookingseat.availability.model;

/** What the availability needs to know about a trip itself: whether it is open, and how many stops it has. */
public record TripHeader(long id, String status, int stopCount) {
}
