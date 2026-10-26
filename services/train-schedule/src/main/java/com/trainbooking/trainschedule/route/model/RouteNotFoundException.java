package com.trainbooking.trainschedule.route.model;

public class RouteNotFoundException extends RuntimeException {

    public RouteNotFoundException(long id) {
        super("Route " + id + " does not exist.");
    }
}
