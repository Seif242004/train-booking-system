package com.trainbooking.bookingseat.ticket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What Booking and Seat needs from the Ticket service. An interface so that the jobs of the outbox can be tested without it. Every method
 * is idempotent, because the outbox repeats a job until it succeeds. Throws
 * {@link com.trainbooking.bookingseat.common.RemoteServiceException} when the answer is not known.
 */
public interface TicketPort {

    /** One seat, or the standing places of one coach, as printed on the ticket. */
    record Line(String type, String coachCode, String coachClass, String seatNumber, int qty, BigDecimal price) {
    }

    /** Everything printed on the ticket (SRS FR-T2). */
    record TicketData(String reference, String passengerName, String trainNumber, String trainName, String fromStation, OffsetDateTime departure,
                      String toStation, OffsetDateTime arrival, List<Line> lines, BigDecimal amount, String currency) {
    }

    /** Makes the ticket of a booking, or does nothing if it exists (the same ticket stays, even if it was invalidated). */
    void issue(long bookingId, TicketData data);

    /** The PDF of the ticket. */
    byte[] pdf(long bookingId);

    /** The booking was cancelled: the QR token stops being valid. A booking without a ticket is not an error. */
    void invalidate(long bookingId);
}
