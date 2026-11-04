package com.trainbooking.bookingseat.board.service;

import com.trainbooking.bookingseat.board.model.SeatCounts;
import com.trainbooking.bookingseat.board.model.TripState;
import java.time.Instant;

/** The lifecycle of a trip on the board. Plain Java with no database, so it is tested on its own. */
public final class TripLifecycle {

    private TripLifecycle() {
    }

    /**
     * CANCELLED wins (a trip in CANCELLING is shown as cancelled too: its bookings are being cancelled). Otherwise the trip is UPCOMING until it
     * leaves its first stop, DEPARTED until it reaches its last stop, then ARRIVED.
     */
    public static TripState of(String tripStatus, Instant departure, Instant arrival, Instant now) {
        if (!"OPEN".equals(tripStatus)) {
            return TripState.CANCELLED;
        }
        if (now.isBefore(departure)) {
            return TripState.UPCOMING;
        }
        return now.isBefore(arrival) ? TripState.DEPARTED : TripState.ARRIVED;
    }

    /** FULL: an upcoming trip that has places in total but none free in any class (seats and standing). */
    public static boolean isFull(TripState state, SeatCounts totals) {
        return state == TripState.UPCOMING && totals.capacity() > 0 && totals.free() == 0;
    }
}
