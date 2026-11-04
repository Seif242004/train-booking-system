package com.trainbooking.trainschedule.coach.model;

/** The train already has a coach with this code (unique per train). Answered as 409 coach_code_taken. */
public class CoachCodeTakenException extends RuntimeException {

    public CoachCodeTakenException(String code) {
        super("This train already has a coach with code " + code + ".");
    }
}
