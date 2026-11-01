package com.trainbooking.trainschedule.trip.model;

import com.trainbooking.trainschedule.common.ApiError;
import java.util.List;

/**
 * Booking and Seat found the snapshot itself invalid (400). That means the data of Train and Schedule does not fit
 * together, for example a seat whose class differs from its coach, so it is a fault to fix, not something to retry.
 * The trip is not saved. Answered as 502 {@code booking_rejected_snapshot}.
 */
public class BookingSnapshotRejectedException extends RuntimeException {

    private final transient List<ApiError.Detail> details;

    public BookingSnapshotRejectedException(String message, List<ApiError.Detail> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
