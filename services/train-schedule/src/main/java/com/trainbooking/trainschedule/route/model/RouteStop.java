package com.trainbooking.trainschedule.route.model;

/**
 * One stop of a route as returned by the API: the SRS columns (stop_index, station_id) plus the station's code, name and
 * city, so a client can show the route without a second request. Segment k is the leg from stop k to stop k+1.
 */
public record RouteStop(int stopIndex, long stationId, String stationCode, String stationName, String stationCity) {
}
