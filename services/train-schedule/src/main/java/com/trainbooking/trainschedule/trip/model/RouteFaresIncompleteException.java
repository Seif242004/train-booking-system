package com.trainbooking.trainschedule.trip.model;

import com.trainbooking.trainschedule.common.ApiError;
import java.util.List;

/**
 * The route has no fare for some segment and class that the train needs, so a ticket for that part of the journey could
 * not be priced. The details name each missing fare. Answered as 409 route_fares_incomplete.
 */
public class RouteFaresIncompleteException extends RuntimeException {

    private final transient List<ApiError.Detail> details;

    public RouteFaresIncompleteException(List<ApiError.Detail> details) {
        super("The fares of this route are not complete for this train. Set the missing fares first.");
        this.details = List.copyOf(details);
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
