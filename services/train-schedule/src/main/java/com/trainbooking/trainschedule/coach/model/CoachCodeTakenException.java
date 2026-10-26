package com.trainbooking.trainschedule.coach.model;

public class CoachCodeTakenException extends RuntimeException {

    public CoachCodeTakenException(String code) {
        super("This train already has a coach with code " + code + ".");
    }
}
