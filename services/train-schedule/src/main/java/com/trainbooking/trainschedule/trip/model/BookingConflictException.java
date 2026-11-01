package com.trainbooking.trainschedule.trip.model;

import com.trainbooking.trainschedule.common.ApiError;
import java.util.List;

/**
 * Booking and Seat refused a change to a trip because of what it holds for it (holds or bookings, a trip that is being
 * cancelled). The trip is not saved. Answered as 409 {@code booking_conflict} with Booking's own explanation.
 */
public class BookingConflictException extends RuntimeException {

    private final transient List<ApiError.Detail> details;

    public BookingConflictException(String message, List<ApiError.Detail> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
