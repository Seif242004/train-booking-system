package com.trainbooking.trainschedule.train.model;

/** No train has this id. Answered as 404 train_not_found. */
public class TrainNotFoundException extends RuntimeException {

    public TrainNotFoundException(long id) {
        super("Train " + id + " does not exist.");
    }
}
