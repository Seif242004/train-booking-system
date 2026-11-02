package com.trainbooking.bookingseat.tripcancel.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.PaymentStartedMeanwhileException;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.booking.repository.InventoryRepository;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import com.trainbooking.bookingseat.booking.service.HoldReleaseService;
import com.trainbooking.bookingseat.common.RemoteServiceException;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import com.trainbooking.bookingseat.live.AvailabilityEvents;
import com.trainbooking.bookingseat.payment.PaymentPort;
import com.trainbooking.bookingseat.payment.PaymentUnavailableException;
import com.trainbooking.bookingseat.schedule.TrainSchedulePort;
import com.trainbooking.bookingseat.tripcancel.model.TripCancellationStatus;
import com.trainbooking.bookingseat.tripcancel.repository.TripCancellationRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An admin cancels a whole trip (Flow 8, SRS 7.3, FR-C8). It is built to be retried: every step is idempotent and each booking is handled
 * on its own, so a failure part-way only leaves work for the next pass, which an admin triggers again or {@link TripCancellationWorker}
 * does by itself.
 *
 * <ol>
 *   <li><b>Request</b> (one transaction, trip row locked): the trip must not have left its first stop. It becomes CANCELLING, which closes it
 *       to new holds at once (a pick locks the trip row and refuses a trip that is not OPEN).</li>
 *   <li><b>Train &amp; Schedule</b> is told the trip is cancelled so it leaves the search results; repeated until it accepts.</li>
 *   <li><b>Active holds:</b> the pending payment is cancelled FIRST (never a seat released while a payment could still be paid), then the hold is
 *       released as EXPIRED and its user's browser is sent back with "This trip was cancelled". A payment that already succeeded or is still
 *       processing waits for the next pass: Payment's report turns it into a confirmed booking, which step 4 then cancels and refunds.</li>
 *   <li><b>Confirmed bookings:</b> each is cancelled with the same {@link CancellationService} as a passenger's or a support cancellation
 *       (seats given back, QR invalidated, full refund, email), one transaction per booking.</li>
 *   <li>When nothing is left (and Train &amp; Schedule knows), the trip becomes CANCELLED and "trip cancelled" is pushed to everyone viewing it.
 *       Until then it is CANCELLING, shown to the admin as "CANCELLED - bookings pending".</li>
 * </ol>
 */
@Service
public class TripCancellationService {

    private static final Logger log = LoggerFactory.getLogger(TripCancellationService.class);
    private static final int MAX_REASON = 500;

    private final TripCancellationRepository trips;
    private final BookingRepository bookings;
    private final InventoryRepository inventory;
    private final PaymentPort payments;
    private final HoldReleaseService releases;
    private final CancellationService cancellations;
    private final TrainSchedulePort trainSchedule;
    private final AvailabilityEvents events;
    private final AuditTrail audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TripCancellationService(TripCancellationRepository trips, BookingRepository bookings, InventoryRepository inventory, PaymentPort payments,
                                   HoldReleaseService releases, CancellationService cancellations, TrainSchedulePort trainSchedule, AvailabilityEvents events,
                                   AuditTrail audit, TransactionTemplate tx, Clock clock) {
        this.trips = trips;
        this.bookings = bookings;
        this.inventory = inventory;
        this.payments = payments;
        this.releases = releases;
        this.cancellations = cancellations;
        this.trainSchedule = trainSchedule;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Starts the cancellation (or repeats it: asking again for a trip that is already CANCELLING or CANCELLED just does another pass, with the
     * reason of the first request) and does one pass at once.
     *
     * @throws TripNotFoundException a trip Booking has no copy of
     * @throws BookingConflictException {@code trip_departed}: the trip has already left its first stop, so it can no longer be cancelled
     */
    public TripCancellationStatus request(String adminId, long tripId, String reason) {
        tx.executeWithoutResult(status -> {
            String state = inventory.lockTrip(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
            if (!"OPEN".equals(state)) {
                return; // a retry: CANCELLING or CANCELLED already, the pass below finishes whatever is left
            }
            boolean departed = inventory.departureTime(tripId, 0).map(departure -> !departure.toInstant().isAfter(clock.instant())).orElse(true);
            if (departed) {
                throw new BookingConflictException("trip_departed", "The trip has already left its first stop, so it can no longer be cancelled.");
            }
            trips.markCancelling(tripId, reason, adminId, now());
            audit.record(adminId, "trip", tripId, "CANCEL_REQUEST", Map.of("reason", reason));
        });
        return process(tripId);
    }

    /** One pass over a trip that is being cancelled. Safe to call at any time, also concurrently with another pass. */
    public TripCancellationStatus process(long tripId) {
        TripCancellationRepository.State state = trips.find(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        if (!"CANCELLING".equals(state.status())) {
            return status(tripId);
        }
        String admin = state.cancelledBy() == null ? "system" : state.cancelledBy();

        // 2. Train & Schedule: the trip leaves the search results. Retried (by the next pass) until it accepts.
        boolean notified = state.trainScheduleNotified();
        if (!notified) {
            try {
                trainSchedule.markTripCancelled(tripId, admin);
                tx.executeWithoutResult(status -> trips.setTrainScheduleNotified(tripId));
                notified = true;
            } catch (RemoteServiceException e) {
                log.warn("Train & Schedule did not accept the cancellation of trip {} yet: {}", tripId, e.getMessage());
            }
        }

        // 3. Active holds: cancel the payment first, then release.
        // Each hold on its own too: one that keeps failing must not keep the confirmed bookings from being refunded.
        for (long holdId : bookings.findPendingIdsOfTrip(tripId)) {
            try {
                releaseHold(holdId, admin);
            } catch (RuntimeException e) {
                log.warn("Hold {} of cancelled trip {} could not be released yet: {}", holdId, tripId, e.getMessage());
            }
        }

        // 4. Confirmed bookings, each on its own: one that fails does not stop the others. The boarding-stop cutoff does not apply (the trip
        // is cancelled), so a later pass still refunds a booking whose train has left in the meantime.
        String why = cancelReason(state.reason());
        for (long bookingId : trips.findConfirmedBookingIds(tripId)) {
            try {
                cancellations.cancelForTrip(CancellationService.Actor.admin(admin), bookingId, why);
            } catch (RuntimeException e) {
                log.warn("Booking {} of cancelled trip {} could not be cancelled yet: {}", bookingId, tripId, e.getMessage());
            }
        }

        // 5. Everything done: CANCELLED, and everyone viewing the trip is told.
        if (notified && trips.count(tripId, "PENDING_PAYMENT") == 0 && trips.count(tripId, "CONFIRMED") == 0) {
            tx.executeWithoutResult(status -> {
                inventory.lockTrip(tripId);
                if (trips.markCancelled(tripId)) {
                    events.tripCancelled(tripId);
                    audit.record(admin, "trip", tripId, "CANCELLED", Map.of("cancelledBookings", trips.count(tripId, "CANCELLED")));
                }
            });
        }
        return status(tripId);
    }

    /** booking.cancel_reason is VARCHAR2(500 CHAR) and the admin's reason may be 500 characters itself: cut the text, never fail the cancellation. */
    static String cancelReason(String reason) {
        String why = "The trip was cancelled" + (reason == null || reason.isBlank() ? "." : ": " + reason.trim());
        return why.length() <= MAX_REASON ? why : why.substring(0, MAX_REASON);
    }

    /** Cancels the payment of a hold first, then releases it with the message "This trip was cancelled". */
    private void releaseHold(long bookingId, String admin) {
        Booking hold = bookings.find(bookingId).orElse(null);
        if (hold == null || hold.status() != BookingStatus.PENDING_PAYMENT) {
            return;
        }
        boolean paymentStarted = hold.paymentStarted();
        PaymentPort.CancelOutcome outcome = PaymentPort.CancelOutcome.NONE;
        if (paymentStarted) {
            try {
                outcome = payments.cancelPending(bookingId); // before anything is released
            } catch (PaymentUnavailableException e) {
                log.warn("The payment of hold {} could not be cancelled yet: {}", bookingId, e.getMessage());
                return;
            }
        }
        if (outcome == PaymentPort.CancelOutcome.SUCCEEDED || outcome == PaymentPort.CancelOutcome.PROCESSING) {
            return; // the money is (or may be) taken: Payment's report confirms it, and the next pass cancels and refunds the booking
        }
        try {
            releases.release(bookingId, HoldReleaseService.Reason.TRIP_CANCELLED, false, admin, paymentStarted);
        } catch (PaymentStartedMeanwhileException e) {
            // The payment page was opened between our look and our lock: ask Payment at the next pass.
            log.info("Hold {} got a payment while its trip was being cancelled: next pass", bookingId);
        }
    }

    public TripCancellationStatus status(long tripId) {
        TripCancellationRepository.State state = trips.find(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        return new TripCancellationStatus(tripId, state.status(), state.reason(), state.cancelledBy(), state.trainScheduleNotified(),
                trips.count(tripId, "PENDING_PAYMENT"), trips.count(tripId, "CONFIRMED"), trips.count(tripId, "CANCELLED"), trips.countRefundsFailed(tripId));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
