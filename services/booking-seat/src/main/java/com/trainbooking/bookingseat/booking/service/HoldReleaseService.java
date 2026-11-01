package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.PaymentStartedMeanwhileException;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.live.AvailabilityEvents;
import com.trainbooking.bookingseat.live.UserEvent;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases the seats of a hold that is over: the five minutes ran out (EXPIRED), the trip was cancelled (EXPIRED) or the user let
 * go of it (ABANDONED). It is one transaction: lock the booking, give the seats and standing places back, set the status,
 * announce it. <b>It must only be called once the pending Stripe payment is cancelled</b> (SRS 4.2): the sweeper does that first,
 * outside any transaction, and only then calls this. It does not cancel by itself, because that is a call to Stripe and a
 * transaction must not hold one open.
 */
@Service
public class HoldReleaseService {

    /** Why a hold ended; the user's browser shows a different message for each. */
    public enum Reason { EXPIRED, TRIP_CANCELLED, RELEASED }

    private final BookingRepository bookings;
    private final BookingLocks locks;
    private final BookingInventory inventory;
    private final AvailabilityEvents events;
    private final AuditTrail audit;
    private final Clock clock;

    public HoldReleaseService(BookingRepository bookings, BookingLocks locks, BookingInventory inventory, AvailabilityEvents events, AuditTrail audit, Clock clock) {
        this.bookings = bookings;
        this.locks = locks;
        this.inventory = inventory;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param onlyIfPastExpiry true for the sweeper: a hold that has not run out is left alone, whatever the caller thinks
     * @param actor            who it is for the audit log: the user id, an admin id, or "system" for the sweeper
     * @param paymentWasStarted true if the caller saw that the user had been to the payment page and cancelled the payment first. If the
     *                         payment page was opened after that look, the release is refused (rolled back) so the caller cancels
     *                         the new payment first; the sweeper always cancels first and passes true.
     * @return true if the hold was released; false if the booking is not a pending hold any more (it was paid, or already released)
     */
    @Transactional
    public boolean release(long bookingId, Reason reason, boolean onlyIfPastExpiry, String actor, boolean paymentWasStarted) {
        // The locks (the trip, then the booking: the one lock order of every inventory change) make this safe against a payment that
        // is confirmed at the same moment: whoever gets the rows first decides, and the other one then sees the new status.
        Booking booking = locks.lock(bookingId).orElse(null);
        if (booking == null || booking.status() != BookingStatus.PENDING_PAYMENT) {
            return false;
        }
        if (onlyIfPastExpiry && booking.expiresAt().toInstant().isAfter(clock.instant())) {
            return false;
        }
        if (booking.paymentStarted() && !paymentWasStarted) {
            throw new PaymentStartedMeanwhileException();
        }
        inventory.giveBack(booking);
        BookingStatus target = reason == Reason.RELEASED ? BookingStatus.ABANDONED : BookingStatus.EXPIRED;
        bookings.markStatus(bookingId, target);
        events.toUser(UserEvent.HOLD_EXPIRED, booking.userId(), booking.tripId(), bookingId, reason.name());
        audit.record(actor, "booking", bookingId, target == BookingStatus.ABANDONED ? "ABANDON" : "EXPIRE", Map.of("reason", reason.name(), "tripId", booking.tripId()));
        return true;
    }
}
