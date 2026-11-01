package com.trainbooking.bookingseat.availability.model;

/** The availability of one trip for one journey, with the status of the trip. */
public record TripAvailability(long tripId, String tripStatus, JourneyAvailability availability) {
}
