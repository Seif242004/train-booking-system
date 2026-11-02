package com.trainbooking.bookingseat.tripcancel.model;

/**
 * Where the cancellation of a trip stands (Flow 8). {@code status} is Booking's own copy of the trip: OPEN (not cancelled), CANCELLING
 * (shown to the admin as "CANCELLED - bookings pending": something is left to do, a retry is safe) or CANCELLED (everything is done).
 *
 * @param trainScheduleNotified Train & Schedule has accepted that the trip is cancelled (it left the search results)
 * @param pendingHolds          holds on the trip that are not released yet (their payment could not be cancelled yet)
 * @param pendingConfirmed      confirmed bookings not cancelled yet
 * @param cancelledBookings     bookings of the trip that are cancelled
 * @param refundsFailed         cancelled bookings whose refund failed, for the admin to follow up
 */
public record TripCancellationStatus(
        long tripId,
        String status,
        String reason,
        String cancelledBy,
        boolean trainScheduleNotified,
        int pendingHolds,
        int pendingConfirmed,
        int cancelledBookings,
        int refundsFailed) {
}
