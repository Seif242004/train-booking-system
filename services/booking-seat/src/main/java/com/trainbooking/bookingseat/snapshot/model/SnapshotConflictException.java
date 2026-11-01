package com.trainbooking.bookingseat.snapshot.model;

import com.trainbooking.bookingseat.common.ApiError;
import java.util.List;

/**
 * The snapshot or the delete cannot be applied because of what Booking and Seat already holds for the trip. Answered as
 * 409 with a code that tells the caller why:
 * <ul>
 *   <li>{@code trip_not_open}: the trip is CANCELLING or CANCELLED and no longer accepts changes;</li>
 *   <li>{@code trip_has_reservations}: the trip has holds or bookings, so its stops, times, train, date and fares are
 *       frozen and it cannot be deleted;</li>
 *   <li>{@code capacity_conflict}: a seat that has holds or bookings would be removed, or the standing capacity of a coach
 *       would drop below the places already used (FR-A9).</li>
 * </ul>
 */
public class SnapshotConflictException extends RuntimeException {

    private final String code;
    private final transient List<ApiError.Detail> details;

    public SnapshotConflictException(String code, String message, List<ApiError.Detail> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public String code() {
        return code;
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
