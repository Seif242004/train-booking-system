package com.trainbooking.trainschedule.coach.model;

/** No coach has this id. Answered as 404 coach_not_found. */
public class CoachNotFoundException extends RuntimeException {

    public CoachNotFoundException(long id) {
        super("Coach " + id + " does not exist.");
    }
}
