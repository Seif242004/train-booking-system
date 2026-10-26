package com.trainbooking.trainschedule.trip.model;

/** New trips can only be scheduled for an ACTIVE train. Answered as 409 train_inactive. */
public class TrainInactiveException extends RuntimeException {

    public TrainInactiveException(String trainNumber) {
        super("Train " + trainNumber + " is INACTIVE, so no trip can be scheduled for it. Set it to ACTIVE first.");
    }
}
