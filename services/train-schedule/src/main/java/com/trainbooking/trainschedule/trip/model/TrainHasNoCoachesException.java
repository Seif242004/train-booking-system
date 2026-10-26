package com.trainbooking.trainschedule.trip.model;

/** A train without coaches has no seats or standing places to sell. Answered as 409 train_has_no_coaches. */
public class TrainHasNoCoachesException extends RuntimeException {

    public TrainHasNoCoachesException(String trainNumber) {
        super("Train " + trainNumber + " has no coaches, so no trip can be scheduled for it. Add its coaches first.");
    }
}
