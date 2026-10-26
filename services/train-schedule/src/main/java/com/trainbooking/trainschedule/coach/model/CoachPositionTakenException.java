package com.trainbooking.trainschedule.coach.model;

public class CoachPositionTakenException extends RuntimeException {

    public CoachPositionTakenException(int position) {
        super("This train already has a coach at position " + position + ".");
    }
}
