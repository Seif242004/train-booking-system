package com.trainbooking.bookingseat.schedule;

/**
 * What Booking and Seat tells Train & Schedule (CLAUDE.md: Booking and Seat may call Train & Schedule to mark a trip cancelled, Flow 8).
 * An interface so that the cancellation of a trip can be tested without it. Throws
 * {@link com.trainbooking.bookingseat.common.RemoteServiceException} when the answer is not known; the call is repeated until accepted.
 */
public interface TrainSchedulePort {

    /** The trip is cancelled: it leaves the search results. Idempotent. {@code adminId} is the admin who cancelled it, for the audit log. */
    void markTripCancelled(long tripId, String adminId);
}
