package com.trainbooking.trainschedule.route.model;

/** No route has this id. Answered as 404 route_not_found. */
public class RouteNotFoundException extends RuntimeException {

    public RouteNotFoundException(long id) {
        super("Route " + id + " does not exist.");
    }
}
