package com.trainbooking.trainschedule.station.model;

/**
 * A station, as stored and as returned by the API (SRS section 8: id, code, name, city). A record is an immutable data class.
 * Read by JdbcClient straight from the columns of the same names (the constructor is matched by name), so no mapper code is needed.
 * {@code code} is stored upper-case and is unique; trip search finds stations by it.
 */
public record Station(long id, String code, String name, String city) {
}
