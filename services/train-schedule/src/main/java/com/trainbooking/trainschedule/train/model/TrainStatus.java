package com.trainbooking.trainschedule.train.model;

/** Whether a train can be used for new trips. The SRS gives "active/inactive"; these are the stored values. */
// An INACTIVE train cannot be used for new trips (TripService); existing trips keep running.
public enum TrainStatus {
    ACTIVE,
    INACTIVE
}
