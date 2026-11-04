package com.trainbooking.trainschedule.train.model;

/** Another train already has this number (unique constraint). Answered as 409 train_number_taken. */
public class TrainNumberTakenException extends RuntimeException {

    public TrainNumberTakenException(String number) {
        super("A train with number " + number + " already exists.");
    }
}
