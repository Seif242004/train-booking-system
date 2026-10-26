package com.trainbooking.trainschedule.route.model;

/**
 * The stops of a route would change, but the route has fares. Fares belong to segment positions, so they would no
 * longer describe the same legs. The admin must confirm (clearFares=true) that they are removed. Answered as 409.
 */
public class RouteHasFaresException extends RuntimeException {

    public RouteHasFaresException(long routeId, int fareCount) {
        super("Route " + routeId + " has " + fareCount + " fares. Changing its stops removes them; "
                + "repeat the request with clearFares=true to confirm.");
    }
}
