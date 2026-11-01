package com.trainbooking.bookingseat.booking.model;

/** A conditional update of a standing counter touched fewer rows than the journey has segments: the places are gone. Rolls the hold back. */
public class StandingUnavailableException extends RuntimeException {

    private final long coachId;

    public StandingUnavailableException(long coachId) {
        super("The standing places of coach " + coachId + " are no longer available.");
        this.coachId = coachId;
    }

    public long coachId() {
        return coachId;
    }
}
