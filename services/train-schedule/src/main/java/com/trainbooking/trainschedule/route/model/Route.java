package com.trainbooking.trainschedule.route.model;

import java.util.List;

/** A route as returned by the API (SRS section 8: route id and name, with its ordered stops). */
public record Route(long id, String name, List<RouteStop> stops) {
}
