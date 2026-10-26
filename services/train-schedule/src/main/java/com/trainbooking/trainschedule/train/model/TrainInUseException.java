package com.trainbooking.trainschedule.train.model;

/** The train has trips, so it cannot be deleted. Answered as 409 train_in_use. */
public class TrainInUseException extends RuntimeException {

    public TrainInUseException(long id) {
        super("Train " + id + " has trips, so it cannot be deleted. Delete its trips first, or set the train to INACTIVE.");
    }
}
