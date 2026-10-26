package com.trainbooking.trainschedule.seat.model;

import com.trainbooking.trainschedule.common.ApiError;
import java.util.List;

/** A seat number or a row and column position that already exists in the coach. Answered as 409 with the details. */
public class SeatConflictException extends RuntimeException {

    private final transient List<ApiError.Detail> details;

    public SeatConflictException(List<ApiError.Detail> details) {
        super("A seat number or a row and column position already exists in this coach.");
        this.details = List.copyOf(details);
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
