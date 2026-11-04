package com.trainbooking.bookingseat.board.model;

import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.RequestValidationException;
import java.util.List;

/** Helper for the board's own input checks, answered as the usual 400 validation error. */
public final class BoardRangeException {

    private BoardRangeException() {
    }

    public static RequestValidationException of(String field, String message) {
        return new RequestValidationException(List.of(new ApiError.Detail(field, message)));
    }
}
