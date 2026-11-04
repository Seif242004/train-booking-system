package com.trainbooking.bookingseat.availability.model;

/** What the availability needs to know about a trip itself: whether it is open, and how many stops it has. */
// "status" is the trip copy's status: OPEN, CANCELLING or CANCELLED. The stop count bounds the valid stop indexes.
public record TripHeader(long id, String status, int stopCount) {
}
