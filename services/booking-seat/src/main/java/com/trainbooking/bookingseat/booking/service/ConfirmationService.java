package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.booking.repository.InventoryRepository;
import com.trainbooking.bookingseat.live.AvailabilityEvents;
import com.trainbooking.bookingseat.live.SeatEvent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirms a paid booking (SRS 7.1, step 4): in one transaction, check that the hold still belongs to this booking, set it
 * CONFIRMED and clear the expiry of its reservation rows, so the sweeper never releases them. It is called when Payment reports
 * that the payment succeeded (and by the sweeper when Stripe says the payment won the race with the timeout).
 */
@Service
public class ConfirmationService {

    /** What a request to confirm found. */
    public enum Result {
        /** The booking was a hold and is confirmed now. */
        CONFIRMED,
        /** It was confirmed already (a repeated report): nothing changed. */
        ALREADY_CONFIRMED,
        /** The hold is gone (expired, abandoned or cancelled): the money cannot become a booking, Payment must refund it. */
        NOT_CONFIRMABLE,
        NOT_FOUND
    }

    private final BookingRepository bookings;
    private final BookingLocks locks;
    private final InventoryRepository inventory;
    private final AvailabilityEvents events;
    private final AuditTrail audit;
    private final Clock clock;

    public ConfirmationService(BookingRepository bookings, BookingLocks locks, InventoryRepository inventory, AvailabilityEvents events, AuditTrail audit, Clock clock) {
        this.bookings = bookings;
        this.locks = locks;
        this.inventory = inventory;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Result confirm(long bookingId) {
        Booking booking = locks.lock(bookingId).orElse(null); // the trip first, then the booking: the one lock order
        if (booking == null) {
            return Result.NOT_FOUND;
        }
        if (booking.status() == BookingStatus.CONFIRMED) {
            return Result.ALREADY_CONFIRMED;
        }
        if (booking.status() != BookingStatus.PENDING_PAYMENT) {
            return Result.NOT_CONFIRMABLE;
        }
        // The payment wins even if the hold has just run out, as long as the sweeper has not released it yet: the money is taken.
        List<Long> seatIds = inventory.findReservedSeatIds(bookingId);
        List<Integer> segments = inventory.findReservedSegments(bookingId);
        inventory.confirmReservations(bookingId);
        bookings.markConfirmed(bookingId, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        events.seats(SeatEvent.SEAT_BOOKED, booking.tripId(), seatIds, segments);
        audit.record("system", "booking", bookingId, "CONFIRM", Map.of("tripId", booking.tripId(), "amount", booking.amount()));
        return Result.CONFIRMED;
    }
}
