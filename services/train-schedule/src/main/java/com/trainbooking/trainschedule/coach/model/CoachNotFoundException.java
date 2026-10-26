package com.trainbooking.trainschedule.coach.model;

public class CoachNotFoundException extends RuntimeException {

    public CoachNotFoundException(long id) {
        super("Coach " + id + " does not exist.");
    }
}
