package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.booking.repository.InventoryRepository;
import com.trainbooking.bookingseat.outbox.model.OutboxMessage;
import com.trainbooking.bookingseat.outbox.service.OutboxWriter;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cancellation of a confirmed booking (Flow 6, SRS 7.2): <b>one implementation</b> for both ways, the passenger cancelling their own booking
 * from "My bookings" (instant, no approval) and an admin cancelling on the passenger's request (with a reason). Whole-trip cancellation
 * (Flow 8) runs this per booking too.
 *
 * <p>Everything that must be true together happens in ONE transaction under the booking lock (the usual lock order, trip then booking):
 * the booking is re-checked, the seats and standing places are given back (the SEAT_RELEASED events go out after the commit), the booking
 * is set CANCELLED with who did it, and the follow-up work is queued in the outbox: invalidate the ticket, ask Payment for the full refund,
 * email the passenger. Those calls are made afterwards, with retries, so a Ticket, Payment or Notification that is down never blocks or
 * undoes the cancellation, and nothing is refunded or emailed for a cancellation that rolled back.
 */
@Service
public class CancellationService {

    /** Who cancels: the user (their own booking) or an admin. {@code id} is the Keycloak subject. */
    public record Actor(String id, Role role) {

        public enum Role { USER, ADMIN }

        public static Actor user(String id) {
            return new Actor(id, Role.USER);
        }

        public static Actor admin(String id) {
            return new Actor(id, Role.ADMIN);
        }
    }

    private final BookingRepository bookings;
    private final InventoryRepository inventoryRows;
    private final BookingLocks locks;
    private final BookingInventory inventory;
    private final OutboxWriter outbox;
    private final AuditTrail audit;
    private final BookingQueryService views;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CancellationService(BookingRepository bookings, InventoryRepository inventoryRows, BookingLocks locks, BookingInventory inventory, OutboxWriter outbox,
                               AuditTrail audit, BookingQueryService views, TransactionTemplate tx, Clock clock) {
        this.bookings = bookings;
        this.inventoryRows = inventoryRows;
        this.locks = locks;
        this.inventory = inventory;
        this.outbox = outbox;
        this.audit = audit;
        this.views = views;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param reason required for an admin, ignored for a passenger (no reason is stored for a passenger)
     * @return the booking as it is now. An already cancelled booking is returned as it is (a retry is safe).
     * @throws BookingNotFoundException a booking that does not exist, or (for a passenger) is somebody else's: 404, so its existence is not revealed
     * @throws BookingConflictException {@code not_cancellable} (it is not a confirmed booking) or {@code trip_departed} (the train has left the boarding stop)
     */
    public BookingView cancel(Actor actor, long bookingId, String reason) {
        return cancel(actor, bookingId, reason, true);
    }

    /**
     * The same cancellation for a booking of a trip an admin is cancelling as a whole (Flow 8): the boarding-stop cutoff does not apply, because
     * the trip itself is cancelled and every confirmed booking must be refunded, also when a later pass reaches it after its train has left.
     */
    public BookingView cancelForTrip(Actor admin, long bookingId, String reason) {
        return cancel(admin, bookingId, reason, false);
    }

    private BookingView cancel(Actor actor, long bookingId, String reason, boolean enforceCutoff) {
        try {
            tx.executeWithoutResult(status -> cancelInTransaction(actor, bookingId, reason, enforceCutoff));
        } catch (org.springframework.dao.DeadlockLoserDataAccessException | org.springframework.dao.CannotAcquireLockException e) {
            // ORA-00060 or a lock timeout: treated as a conflict (CLAUDE.md). Nothing was changed; trying again works.
            throw new BookingConflictException("busy", "Another change to this booking is in progress. Try again in a moment.");
        }
        return views.view(bookingId);
    }

    private void cancelInTransaction(Actor actor, long bookingId, String reason, boolean enforceCutoff) {
        Booking booking = locks.lock(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (actor.role() == Actor.Role.USER && !booking.userId().equals(actor.id())) {
            throw new BookingNotFoundException(bookingId);
        }
        if (booking.status() == BookingStatus.CANCELLED) {
            return; // a retry, or two clicks: the current state is the answer
        }
        if (booking.status() != BookingStatus.CONFIRMED) {
            throw new BookingConflictException("not_cancellable", "Only a confirmed booking can be cancelled.");
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        // The cutoff: the train has not yet left the boarding stop of this booking.
        boolean departed = inventoryRows.departureTime(booking.tripId(), booking.fromStop()).map(departure -> !departure.toInstant().isAfter(clock.instant())).orElse(true); // no stop row: fail closed
        if (enforceCutoff && departed) {
            throw new BookingConflictException("trip_departed", "The train has already left your boarding stop, so this booking can no longer be cancelled.");
        }

        inventory.giveBack(booking);
        boolean byAdmin = actor.role() == Actor.Role.ADMIN;
        bookings.markCancelled(bookingId, now, actor.id(), actor.role().name(), byAdmin ? reason : null, RefundStatus.REFUND_PENDING);
        // In this order, per booking: the QR stops working, the money is asked back, the passenger is told.
        outbox.queue(OutboxMessage.Kind.TICKET_INVALIDATE, bookingId);
        outbox.queue(OutboxMessage.Kind.REFUND_REQUEST, bookingId);
        outbox.queue(OutboxMessage.Kind.CANCELLATION_EMAIL, bookingId);

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("role", actor.role().name());
        diff.put("tripId", booking.tripId());
        diff.put("amount", booking.amount());
        if (byAdmin) {
            diff.put("reason", reason);
        }
        audit.record(actor.id(), "booking", bookingId, "CANCEL", diff);
    }
}
