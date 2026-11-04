package com.trainbooking.trainschedule.station.model;

/** Another station already uses this code. Thrown by StationService on the unique-constraint error; answered as 409 station_code_taken. */
public class StationCodeTakenException extends RuntimeException {

    public StationCodeTakenException(String code) {
        super("A station with code " + code + " already exists.");
    }
}
