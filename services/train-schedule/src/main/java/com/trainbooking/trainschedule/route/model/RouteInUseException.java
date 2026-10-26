package com.trainbooking.trainschedule.route.model;

/**
 * The route has trips. Its stops are copied into every trip, so they cannot change, and it cannot be deleted. Answered
 * as 409 route_in_use.
 */
public class RouteInUseException extends RuntimeException {

    public RouteInUseException(long id) {
        super("Route " + id + " is used by trips, so its stops cannot change and it cannot be deleted. Delete its trips first.");
    }
}
