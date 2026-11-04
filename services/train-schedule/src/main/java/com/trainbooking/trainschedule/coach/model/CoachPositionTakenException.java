package com.trainbooking.trainschedule.coach.model;

/** The train already has a coach at this position (unique per train). Answered as 409 coach_position_taken. */
public class CoachPositionTakenException extends RuntimeException {

    public CoachPositionTakenException(int position) {
        super("This train already has a coach at position " + position + ".");
    }
}
