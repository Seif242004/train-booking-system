package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.availability.model.TripHeader;
import com.trainbooking.bookingseat.availability.repository.AvailabilityRepository;
import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldConflictException;
import com.trainbooking.bookingseat.booking.model.HoldExpiredException;
import com.trainbooking.bookingseat.booking.model.PaymentStartedMeanwhileException;
import com.trainbooking.bookingseat.booking.model.PickRequest;
import com.trainbooking.bookingseat.booking.model.ReleaseRequest;
import com.trainbooking.bookingseat.booking.model.StandingUnavailableException;
import com.trainbooking.bookingseat.booking.model.TripClosedException;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.booking.repository.InventoryRepository;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import com.trainbooking.bookingseat.live.AvailabilityEvents;
import com.trainbooking.bookingseat.live.SeatEvent;
import com.trainbooking.bookingseat.payment.PaymentPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Picking and releasing seats (Flow 3, SRS FR-H1 to FR-H7). <b>The hold starts at the first pick</b>: the 5 minutes begin when a
 * user picks the first seat or standing place on a trip, every further pick joins the same booking and never extends the timer,
 * deselecting releases at once, and releasing the last item ends the hold (ABANDONED).
 *
 * <p>Every pick and every release is <b>its own all-or-nothing database transaction</b>, so a pick that loses a race fails alone and
 * the seats already held stay held. The database decides: the primary key of {@code seat_reservation} rejects a seat that is taken on
 * any segment (ORA-00001), and a conditional update that changes no row means that the standing places are gone. A user has at most
 * one active hold per trip (a unique index), and its journey is the journey of the first pick.
 *
 * <p>If the user has already been to the payment page, the open Stripe payment is cancelled first (outside any transaction) before
 * the hold changes, so that the amount of a payment never differs from the booking. The amount is always worked out here from the
 * fares of the copy of the trip.
 */
@Service
public class HoldService {

    /** SRS FR-H3: a hold is valid for exactly 5 minutes from the first pick. */
    public static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    /** Limits that keep one hold reasonable: it is a booking for one party, not a way to lock a whole train. */
    static final int MAX_SEATS = 20;
    static final int MAX_STANDING_PER_COACH = 20;

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    /** How often a change is tried again when the user opens the payment page at the very moment it is made. */
    private static final int SETTLE_ATTEMPTS = 3;

    /** The result of a pick: the hold, and whether this pick started it. */
    public record PickResult(BookingView booking, boolean started) {
    }

    /** What a pick is about, worked out before the transaction: the journey, the seat or the coach, and the price of the pick. */
    private record Prepared(PickRequest request, Journey journey, InventoryRepository.SeatRef seat, InventoryRepository.CoachRef coach,
                            BookingPricing.Line line, BigDecimal unitPrice) {
    }

    private record Picked(long bookingId, boolean started) {
    }

    private final TransactionTemplate tx;
    private final BookingRepository bookings;
    private final InventoryRepository inventory;
    private final AvailabilityRepository availabilityRepository;
    private final AvailabilityEvents events;
    private final BookingQueryService views;
    private final BookingLocks locks;
    private final HoldReleaseService releases;
    private final PaymentPort payments;
    private final AuditTrail audit;
    private final Clock clock;

    public HoldService(TransactionTemplate tx, BookingRepository bookings, InventoryRepository inventory, AvailabilityRepository availabilityRepository,
                       AvailabilityEvents events, BookingQueryService views, BookingLocks locks,
                       HoldReleaseService releases, PaymentPort payments,
                       AuditTrail audit, Clock clock) {
        this.tx = tx;
        this.bookings = bookings;
        this.inventory = inventory;
        this.availabilityRepository = availabilityRepository;
        this.events = events;
        this.views = views;
        this.locks = locks;
        this.releases = releases;
        this.payments = payments;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- pick ---------------------------------------------------------------------------------------------------------

    /**
     * Picks a seat or standing places: starts the hold if the user has none on the trip, otherwise adds to it.
     * A pick that loses a race throws {@link HoldConflictException} and changes nothing, so what was picked before stays held.
     */
    public PickResult pick(AuthenticatedUser caller, PickRequest request) {
        AuthenticatedUser user = checked(caller);
        Prepared prepared = prepare(request);

        for (int attempt = 1; ; attempt++) {
            // A user who was at the payment page and came back: the open payment is cancelled first, before the hold changes.
            Booking open = bookings.findActiveHold(user.id(), request.tripId()).orElse(null);
            boolean paymentWasStarted = open != null && open.paymentStarted();
            if (paymentWasStarted && !isOver(open)) {
                settlePaymentBefore(open, false);
            }
            try {
                Picked picked = tx.execute(status -> pickInTransaction(user, prepared, paymentWasStarted));
                return new PickResult(views.view(picked.bookingId()), picked.started());
            } catch (PaymentStartedMeanwhileException e) {
                // The payment page was opened between our look and our lock: nothing was changed; cancel that payment and try again.
                if (attempt >= SETTLE_ATTEMPTS) {
                    throw new BookingConflictException("payment_in_progress", "A payment was just started for this booking. Try again in a moment.");
                }
            } catch (DuplicateKeyException | StandingUnavailableException | PessimisticLockingFailureException e) {
                // The transaction of this pick was rolled back: this pick changed nothing. Say what was lost (FR-H2).
                log.info("Pick refused for trip {}: {}", request.tripId(), e.getClass().getSimpleName());
                throw new HoldConflictException(lostItems(prepared));
            }
        }
    }

    private AuthenticatedUser checked(AuthenticatedUser caller) {
        if (caller.email() == null || caller.email().isBlank()) {
            // The ticket is sent by e-mail: a booking without an address could never be delivered.
            throw new RequestValidationException(List.of(new ApiError.Detail("email", "your account has no e-mail address, which the ticket needs")));
        }
        // The name is only a copy for the ticket: a very long one is cut rather than refused.
        return new AuthenticatedUser(caller.id(), caller.email(), caller.name() == null ? null : caller.name().substring(0, Math.min(200, caller.name().length())));
    }

    private Prepared prepare(PickRequest request) {
        if (request.seatId() == null == (request.coachId() == null)) {
            throw new RequestValidationException(List.of(new ApiError.Detail("seatId", "give either a seat or a coach for standing places, not both and not neither")));
        }
        TripHeader trip = availabilityRepository.findTrip(request.tripId()).orElseThrow(() -> new TripNotFoundException(request.tripId()));
        Journey journey = Journey.of(request.fromStop(), request.toStop(), trip.stopCount());
        if (!"OPEN".equals(trip.status())) {
            throw new TripClosedException("trip_closed", "This trip is closed: it is cancelled or being cancelled.");
        }
        var fares = availabilityRepository.findFares(request.tripId());
        if (request.isSeat()) {
            InventoryRepository.SeatRef seat = inventory.findSeats(request.tripId(), List.of(request.seatId())).get(request.seatId());
            if (seat == null) {
                throw new RequestValidationException(List.of(new ApiError.Detail("seatId", "seat " + request.seatId() + " is not on this trip")));
            }
            BookingPricing.Line line = BookingPricing.price(journey, fares, List.of(new BookingPricing.Seat(seat.seatId(), seat.coachId(), seat.coachClass())), List.of()).get(0);
            return new Prepared(request, journey, seat, null, line, line.price());
        }
        InventoryRepository.CoachRef coach = inventory.findCoaches(request.tripId(), List.of(request.coachId())).get(request.coachId());
        if (coach == null || !coach.hasStanding()) {
            throw new RequestValidationException(List.of(new ApiError.Detail("coachId", "this coach has no standing places on this trip")));
        }
        BookingPricing.Line one = BookingPricing.price(journey, fares, List.of(), List.of(new BookingPricing.Standing(coach.coachId(), coach.coachClass(), 1))).get(0);
        return new Prepared(request, journey, null, coach, one, one.price());
    }

    /** @param paymentWasStarted whether the caller saw the payment page opened and cancelled the payment before this transaction */
    private Picked pickInTransaction(AuthenticatedUser user, Prepared p, boolean paymentWasStarted) {
        PickRequest request = p.request();
        long tripId = request.tripId();
        Instant now = clock.instant();

        // The lock order of every inventory change: the row of the trip first, then the booking, then seats and counters.
        String status = inventory.lockTrip(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        if (!"OPEN".equals(status)) {
            throw new TripClosedException("trip_closed", "This trip is closed: it is cancelled or being cancelled.");
        }
        // The train must not have left the boarding stop (the cutoff the cancellation uses too).
        inventory.departureTime(tripId, p.journey().fromStop()).ifPresent(departure -> {
            if (!departure.toInstant().isAfter(now)) {
                throw new TripClosedException("trip_departed", "The train has already left the boarding stop.");
            }
        });

        Booking hold = bookings.findActiveHoldForUpdate(user.id(), tripId).orElse(null);
        boolean started = hold == null;
        long bookingId;
        OffsetDateTime expiresAt;
        if (started) {
            // The first pick: the booking is created and its 5 minutes start now.
            expiresAt = OffsetDateTime.ofInstant(now.plus(HOLD_DURATION), ZoneOffset.UTC);
            bookingId = insertBooking(user, p, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), expiresAt);
        } else {
            if (isOver(hold)) {
                throw new HoldExpiredException(); // the timer is never extended: the sweeper releases it, the user starts again
            }
            if (hold.fromStop() != p.journey().fromStop() || hold.toStop() != p.journey().toStop()) {
                throw new BookingConflictException("hold_for_another_journey",
                        "You are already holding seats on this trip for another journey. Release them, or continue to payment, first.");
            }
            bookingId = hold.id();
            expiresAt = hold.expiresAt();
            if (hold.paymentStarted() && !paymentWasStarted) {
                throw new PaymentStartedMeanwhileException(); // see the exception: rolled back, cancel the payment, try again
            }
            // payment_started is never cleared: once the user has been to the payment page, every change goes through Payment (cheap
            // and idempotent when there is nothing open), so a payment that is created later can never be missed.
        }

        List<Integer> segments = IntStream.range(p.journey().fromStop(), p.journey().toStop()).boxed().toList();
        if (request.isSeat()) {
            pickSeat(bookingId, tripId, p, expiresAt, segments);
        } else {
            pickStanding(bookingId, tripId, p, segments);
        }
        bookings.recomputeAmount(bookingId);
        audit.record(user.id(), "booking", bookingId, started ? "HOLD" : "PICK",
                Map.of("tripId", tripId, "fromStop", p.journey().fromStop(), "toStop", p.journey().toStop(),
                        request.isSeat() ? "seatId" : "coachId", request.isSeat() ? request.seatId() : request.coachId(), "qty", request.isSeat() ? 1 : request.places()));
        return new Picked(bookingId, started);
    }

    private void pickSeat(long bookingId, long tripId, Prepared p, OffsetDateTime expiresAt, List<Integer> segments) {
        long seatId = p.request().seatId();
        if (bookings.seatItemExists(bookingId, seatId)) {
            return; // already in this hold: picking it again changes nothing
        }
        if (bookings.countSeatItems(bookingId) >= MAX_SEATS) {
            throw new BookingConflictException("hold_too_large", "A hold can have at most " + MAX_SEATS + " seats.");
        }
        // The seat on every segment of the journey, or the primary key refuses (ORA-00001). The reservation carries the same expiry as
        // the booking: a later pick never gives a seat more time than the hold has.
        inventory.insertReservations(tripId, bookingId, List.of(seatId), p.journey().fromStop(), p.journey().toStop(), expiresAt);
        bookings.insertItem(bookingId, p.line(), p.seat(), null);
        events.seats(SeatEvent.SEAT_HELD, tripId, List.of(seatId), segments);
    }

    private void pickStanding(long bookingId, long tripId, Prepared p, List<Integer> segments) {
        long coachId = p.request().coachId();
        int qty = p.request().places();
        int before = bookings.findStandingQty(bookingId, coachId).orElse(0);
        if (before + qty > MAX_STANDING_PER_COACH) {
            throw new BookingConflictException("hold_too_large", "A hold can have at most " + MAX_STANDING_PER_COACH + " standing places in a coach.");
        }
        for (int segment : segments) { // ascending segments: the same order for every hold, so two holds do not deadlock
            // A conditional update: the counter changes only if the places are still there. No row changed means gone.
            if (!inventory.reserveStandingSegment(tripId, coachId, segment, qty)) {
                throw new StandingUnavailableException(coachId);
            }
        }
        BigDecimal price = p.unitPrice().multiply(BigDecimal.valueOf(before + qty));
        BookingPricing.Line line = new BookingPricing.Line("STANDING", null, coachId, before + qty, price);
        if (before == 0) {
            inventory.insertStandingHold(bookingId, tripId, coachId, p.journey().fromStop(), p.journey().toStop() - 1, qty);
            bookings.insertItem(bookingId, line, null, p.coach());
        } else {
            inventory.setStandingHoldQty(bookingId, coachId, before + qty);
            bookings.updateStandingItem(bookingId, coachId, before + qty, price);
        }
        events.standing(tripId, coachId, -qty, segments);
    }

    /** A clash of the (random) reference is not a clash of seats: it is retried with a new reference. */
    private long insertBooking(AuthenticatedUser user, Prepared p, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        for (int attempt = 0; ; attempt++) {
            try {
                return bookings.insert(BookingReferences.next(), user.id(), user.email(), user.name(), p.request().tripId(), p.journey().fromStop(),
                        p.journey().toStop(), BigDecimal.ZERO, expiresAt, createdAt);
            } catch (DuplicateKeyException e) {
                if (attempt >= 5) {
                    throw e;
                }
            }
        }
    }

    // ---- release --------------------------------------------------------------------------------------------------------

    /**
     * Deselects a seat, or gives back some standing places, at once. If it is the last item of the hold, the hold ends
     * (ABANDONED) and the booking is returned in that state.
     */
    public BookingView release(AuthenticatedUser user, long bookingId, ReleaseRequest request) {
        if (request.seatId() == null == (request.coachId() == null)) {
            throw new RequestValidationException(List.of(new ApiError.Detail("seatId", "give either a seat or a coach for standing places, not both and not neither")));
        }
        for (int attempt = 1; ; attempt++) {
            Booking hold = ownHold(user, bookingId);
            boolean emptiesTheHold = emptiesTheHold(hold, request);
            boolean paymentWasStarted = hold.paymentStarted();
            // Cancel first: an open payment is cancelled before the hold changes (SRS 4.2), outside any transaction.
            if (paymentWasStarted) {
                settlePaymentBefore(hold, emptiesTheHold);
            }
            try {
                if (emptiesTheHold) {
                    releases.release(bookingId, HoldReleaseService.Reason.RELEASED, false, user.id(), paymentWasStarted);
                } else {
                    tx.executeWithoutResult(status -> releaseInTransaction(user, bookingId, request, paymentWasStarted));
                }
                return views.view(bookingId);
            } catch (PaymentStartedMeanwhileException e) {
                if (attempt >= SETTLE_ATTEMPTS) {
                    throw new BookingConflictException("payment_in_progress", "A payment was just started for this booking. Try again in a moment.");
                }
            }
        }
    }

    /** Releases the whole hold at once (FR-H7: for example when the user presses Back or Cancel). */
    public BookingView releaseAll(AuthenticatedUser user, long bookingId) {
        for (int attempt = 1; ; attempt++) {
            Booking hold = ownHold(user, bookingId);
            boolean paymentWasStarted = hold.paymentStarted();
            if (paymentWasStarted) {
                settlePaymentBefore(hold, true);
            }
            try {
                releases.release(bookingId, HoldReleaseService.Reason.RELEASED, false, user.id(), paymentWasStarted);
                return views.view(bookingId);
            } catch (PaymentStartedMeanwhileException e) {
                if (attempt >= SETTLE_ATTEMPTS) {
                    throw new BookingConflictException("payment_in_progress", "A payment was just started for this booking. Try again in a moment.");
                }
            }
        }
    }

    private Booking ownHold(AuthenticatedUser user, long bookingId) {
        Booking hold = bookings.find(bookingId).filter(b -> b.userId().equals(user.id())).orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (hold.status() != BookingStatus.PENDING_PAYMENT) {
            throw new BookingConflictException("not_a_hold", "This booking is not a hold any more.");
        }
        if (isOver(hold)) {
            throw new HoldExpiredException();
        }
        return hold;
    }

    private boolean emptiesTheHold(Booking hold, ReleaseRequest request) {
        if (bookings.countItems(hold.id()) != 1) {
            return false;
        }
        if (request.isSeat()) {
            return bookings.seatItemExists(hold.id(), request.seatId());
        }
        return bookings.findStandingQty(hold.id(), request.coachId()).map(qty -> request.places() >= qty).orElse(false);
    }

    private void releaseInTransaction(AuthenticatedUser user, long bookingId, ReleaseRequest request, boolean paymentWasStarted) {
        Booking hold = locks.lock(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (hold.status() != BookingStatus.PENDING_PAYMENT) {
            throw new BookingConflictException("not_a_hold", "This booking is not a hold any more.");
        }
        if (isOver(hold)) {
            throw new HoldExpiredException();
        }
        if (hold.paymentStarted() && !paymentWasStarted) {
            throw new PaymentStartedMeanwhileException();
        }
        long tripId = hold.tripId();
        if (request.isSeat()) {
            if (bookings.deleteSeatItem(bookingId, request.seatId()) == 0) {
                throw new BookingConflictException("not_picked", "That seat is not in your hold.");
            }
            List<Integer> segments = inventory.deleteSeatReservations(bookingId, request.seatId());
            events.seats(SeatEvent.SEAT_RELEASED, tripId, List.of(request.seatId()), segments);
        } else {
            InventoryRepository.StandingHoldRow standing = inventory.findStandingHold(bookingId, request.coachId())
                    .orElseThrow(() -> new BookingConflictException("not_picked", "You hold no standing places in that coach."));
            int give = request.places();
            if (give > standing.qty()) {
                throw new BookingConflictException("not_picked", "You hold only " + standing.qty() + " standing places in that coach.");
            }
            List<Integer> segments = IntStream.rangeClosed(standing.fromSegment(), standing.toSegment()).boxed().toList();
            for (int segment : segments) {
                inventory.releaseStandingSegment(tripId, standing.coachId(), segment, give);
            }
            int left = standing.qty() - give;
            if (left == 0) {
                inventory.deleteStandingHold(bookingId, standing.coachId());
                bookings.deleteStandingItem(bookingId, standing.coachId());
            } else {
                BigDecimal unit = bookings.findItems(bookingId).stream().filter(i -> "STANDING".equals(i.type()) && i.coachId() == standing.coachId())
                        .map(i -> i.price().divide(BigDecimal.valueOf(i.qty()), 2, java.math.RoundingMode.UNNECESSARY)).findFirst().orElseThrow();
                inventory.setStandingHoldQty(bookingId, standing.coachId(), left);
                bookings.updateStandingItem(bookingId, standing.coachId(), left, unit.multiply(BigDecimal.valueOf(left)));
            }
            events.standing(tripId, standing.coachId(), give, segments);
        }
        bookings.recomputeAmount(bookingId);
        audit.record(user.id(), "booking", bookingId, "RELEASE", Map.of("tripId", tripId, request.isSeat() ? "seatId" : "coachId",
                request.isSeat() ? request.seatId() : request.coachId(), "qty", request.isSeat() ? 1 : request.places()));
    }

    // ---- shared -------------------------------------------------------------------------------------------------------------

    private boolean isOver(Booking hold) {
        return !hold.expiresAt().toInstant().isAfter(clock.instant());
    }

    /**
     * The user had been to the payment page: before the hold changes, the open Stripe payment is cancelled (SRS 4.2). If the payment
     * already succeeded, the booking is confirmed instead and the change is refused; if it is still being processed, the change is
     * refused for now. {@code ending} is true when the hold ends (the payment is then closed for good), false when only an item changes
     * (a new payment can be created for the new amount).
     */
    private void settlePaymentBefore(Booking hold, boolean ending) {
        PaymentPort.CancelOutcome outcome = ending ? payments.cancelPending(hold.id()) : payments.invalidate(hold.id());
        switch (outcome) {
            case NONE, CANCELLED -> { /* nothing is open any more: the hold may change */ }
            case SUCCEEDED -> // the money is taken: Payment's report confirms the booking (with the amount checked); the hold must not change
                    throw new BookingConflictException("already_paid", "This booking is already paid.");
            case PROCESSING -> throw new BookingConflictException("payment_in_progress", "The payment is being processed. Try again in a moment.");
        }
    }

    /** After a refused pick: which item was lost (the pick itself failed alone and changed nothing). */
    private List<HoldConflictException.Lost> lostItems(Prepared p) {
        if (p.request().isSeat()) {
            return List.of(new HoldConflictException.Lost("SEAT", p.request().seatId(), "seat " + p.seat().seatNumber() + " of coach " + p.seat().coachCode()));
        }
        return List.of(new HoldConflictException.Lost("STANDING", p.request().coachId(), "standing places of coach " + p.coach().code()));
    }
}
