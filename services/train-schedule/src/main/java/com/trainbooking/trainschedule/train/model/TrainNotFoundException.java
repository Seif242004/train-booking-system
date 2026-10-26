package com.trainbooking.trainschedule.train.model;

public class TrainNotFoundException extends RuntimeException {

    public TrainNotFoundException(long id) {
        super("Train " + id + " does not exist.");
    }
}
