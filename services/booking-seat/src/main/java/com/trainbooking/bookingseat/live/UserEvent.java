package com.trainbooking.bookingseat.live;

/**
 * An event for one user only (SRS section 6): HOLD_EXPIRED tells the browser of the user whose hold ran out, or whose trip was
 * cancelled, to go back to the seat map with the right message. Like {@link SeatEvent} it is delivered only after the commit.
 *
 * @param reason EXPIRED (the five minutes ran out), TRIP_CANCELLED or RELEASED (the user let go of the hold)
 */
public record UserEvent(String type, String userId, long tripId, long bookingId, String reason) {

    public static final String HOLD_EXPIRED = "HOLD_EXPIRED";
}
