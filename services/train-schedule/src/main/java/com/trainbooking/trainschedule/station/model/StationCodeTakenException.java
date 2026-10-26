package com.trainbooking.trainschedule.station.model;

public class StationCodeTakenException extends RuntimeException {

    public StationCodeTakenException(String code) {
        super("A station with code " + code + " already exists.");
    }
}
