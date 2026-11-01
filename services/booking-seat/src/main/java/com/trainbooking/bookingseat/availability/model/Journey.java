package com.trainbooking.bookingseat.availability.model;

import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.RequestValidationException;
import java.util.List;

/**
 * A journey from the boarding stop to the destination stop of a trip (stop indexes). It uses the segments
 * {@code fromStop} to {@code toStop - 1}: segment k is the leg from stop k to stop k + 1 (SRS 4.1).
 */
public record Journey(int fromStop, int toStop) {

    /**
     * Checks the stops against the trip: both must be stops of the trip (0 to stopCount - 1) and the destination must come
     * after the boarding stop. Only one problem is reported, on the first field that is wrong.
     */
    public static Journey of(int fromStop, int toStop, int stopCount) {
        if (fromStop < 0 || fromStop >= stopCount) {
            throw problem("from", "must be a stop of the trip (0 to " + (stopCount - 1) + ")");
        }
        if (toStop <= fromStop || toStop >= stopCount) {
            throw problem("to", "must be a stop after 'from', up to the last stop (" + (stopCount - 1) + ")");
        }
        return new Journey(fromStop, toStop);
    }

    /** True if the journey uses this segment. */
    public boolean includes(int segmentIndex) {
        return segmentIndex >= fromStop && segmentIndex < toStop;
    }

    private static RequestValidationException problem(String field, String message) {
        return new RequestValidationException(List.of(new ApiError.Detail(field, message)));
    }
}
