package com.trainbooking.trainschedule.station;

/** A station, as stored and as returned by the API (SRS section 8: id, code, name, city). */
public record Station(long id, String code, String name, String city) {
}
