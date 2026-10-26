package com.trainbooking.trainschedule.train.model;

public class TrainNumberTakenException extends RuntimeException {

    public TrainNumberTakenException(String number) {
        super("A train with number " + number + " already exists.");
    }
}
