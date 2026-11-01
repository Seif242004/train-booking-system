package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.availability.model.JourneyAvailability;
import com.trainbooking.bookingseat.availability.model.SeatState;
import com.trainbooking.bookingseat.availability.model.TripHeader;
import com.trainbooking.bookingseat.availability.repository.AvailabilityRepository;
import com.trainbooking.bookingseat.availability.service.AvailabilityService;
import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldConflictException;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.StandingUnavailableException;
import com.trainbooking.bookingseat.booking.model.TripClosedException;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.booking.repository.InventoryRepository;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import com.trainbooking.bookingseat.live.AvailabilityEvents;
import com.trainbooking.bookingseat.live.SeatEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds seats and standing places (Flow 3, SRS FR-H1 to FR-H3, section 5). The hold is <b>all-or-nothing in one database
 * transaction</b>: the booking, its items, a reservation row for every seat on every segment of the journey and a conditional
 * update of the standing counters. The database decides: the primary key of {@code seat_reservation} rejects a seat that is
 * taken on any segment (ORA-00001), and a conditional update that touches no row means the standing places are gone. Either one
 * rolls everything back and the user is told which items were lost.
 *
 * <p>The amount is worked out here from the fares of the copy of the trip. The hold lasts exactly 5 minutes from now, by the
 * clock of the server, and cannot be extended.
 */
@Service
public class HoldService {

    /** SRS FR-H3: a hold is valid for exactly 5 minutes. */
    public static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    /** Everything worked out from the request before the transaction: validated, priced, and ready to insert. */
    private record Prepared(HoldRequest request, Journey journey, List<BookingPricing.Line> lines, BigDecimal amount,
                            Map<Long, InventoryRepository.SeatRef> seats, Map<Long, InventoryRepository.CoachRef> coaches) {
    }

    private final TransactionTemplate tx;
    private final BookingRepository bookings;
    private final InventoryRepository inventory;
    private final AvailabilityRepository availabilityRepository;
    private final AvailabilityService availability;
    private final AvailabilityEvents events;
    private final BookingQueryService views;
    private final AuditTrail audit;
    private final Clock clock;

    public HoldService(TransactionTemplate tx, BookingRepository bookings, InventoryRepository inventory, AvailabilityRepository availabilityRepository,
                       AvailabilityService availability, AvailabilityEvents events, BookingQueryService views, AuditTrail audit, Clock clock) {
        this.tx = tx;
        this.bookings = bookings;
        this.inventory = inventory;
        this.availabilityRepository = availabilityRepository;
        this.availability = availability;
        this.events = events;
        this.views = views;
        this.audit = audit;
        this.clock = clock;
    }

    /** @return the new booking, PENDING_PAYMENT, with the time its hold runs out */
    public BookingView hold(AuthenticatedUser caller, HoldRequest request) {
        if (caller.email() == null || caller.email().isBlank()) {
            // The ticket is sent by e-mail: a booking without an address could never be delivered.
            throw new RequestValidationException(List.of(new ApiError.Detail("email", "your account has no e-mail address, which the ticket needs")));
        }
        // The name is only a copy for the ticket: a very long one is cut rather than refused.
        AuthenticatedUser user = new AuthenticatedUser(caller.id(), caller.email(), caller.name() == null ? null : caller.name().substring(0, Math.min(200, caller.name().length())));
        Prepared prepared = prepare(request);
        try {
            long bookingId = tx.execute(status -> insertHold(user, prepared));
            return views.view(bookingId);
        } catch (DuplicateKeyException | StandingUnavailableException | DeadlockLoserDataAccessException | CannotAcquireLockException e) {
            // The transaction was rolled back: nothing is held. Find out what was lost, for the message (FR-H2).
            log.info("Hold refused for trip {}: {}", request.tripId(), e.getClass().getSimpleName());
            throw new HoldConflictException(lostItems(prepared));
        }
    }

    // ---- before the transaction: validate and price -------------------------------------------------------------

    private Prepared prepare(HoldRequest request) {
        TripHeader trip = availabilityRepository.findTrip(request.tripId()).orElseThrow(() -> new TripNotFoundException(request.tripId()));
        Journey journey = Journey.of(request.fromStop(), request.toStop(), trip.stopCount());
        if (!"OPEN".equals(trip.status())) {
            throw new TripClosedException("trip_closed", "This trip is closed: it is cancelled or being cancelled.");
        }

        List<ApiError.Detail> problems = new ArrayList<>();
        if (request.seats().isEmpty() && request.standingPlaces().isEmpty()) {
            problems.add(new ApiError.Detail("seatIds", "choose at least one seat or standing place"));
        }
        Set<Long> seenSeats = new HashSet<>();
        for (int i = 0; i < request.seats().size(); i++) {
            if (!seenSeats.add(request.seats().get(i))) {
                problems.add(new ApiError.Detail("seatIds[" + i + "]", "is listed twice"));
            }
        }
        Set<Long> seenCoaches = new HashSet<>();
        for (int i = 0; i < request.standingPlaces().size(); i++) {
            if (!seenCoaches.add(request.standingPlaces().get(i).coachId())) {
                problems.add(new ApiError.Detail("standing[" + i + "].coachId", "is listed twice"));
            }
        }
        Map<Long, InventoryRepository.SeatRef> seats = inventory.findSeats(request.tripId(), seenSeats);
        for (int i = 0; i < request.seats().size(); i++) {
            if (!seats.containsKey(request.seats().get(i))) {
                problems.add(new ApiError.Detail("seatIds[" + i + "]", "seat " + request.seats().get(i) + " is not on this trip"));
            }
        }
        Map<Long, InventoryRepository.CoachRef> coaches = inventory.findCoaches(request.tripId(), seenCoaches);
        for (int i = 0; i < request.standingPlaces().size(); i++) {
            InventoryRepository.CoachRef coach = coaches.get(request.standingPlaces().get(i).coachId());
            if (coach == null || !coach.hasStanding()) {
                problems.add(new ApiError.Detail("standing[" + i + "].coachId", "this coach has no standing places on this trip"));
            }
        }
        if (!problems.isEmpty()) {
            throw new RequestValidationException(problems);
        }

        List<BookingPricing.Seat> seatsToPrice = request.seats().stream()
                .map(id -> new BookingPricing.Seat(id, seats.get(id).coachId(), seats.get(id).coachClass())).toList();
        List<BookingPricing.Standing> standingToPrice = request.standingPlaces().stream()
                .map(s -> new BookingPricing.Standing(s.coachId(), coaches.get(s.coachId()).coachClass(), s.qty())).toList();
        List<BookingPricing.Line> lines = BookingPricing.price(journey, availabilityRepository.findFares(request.tripId()), seatsToPrice, standingToPrice);
        return new Prepared(request, journey, lines, BookingPricing.total(lines), seats, coaches);
    }

    // ---- the transaction ----------------------------------------------------------------------------------------------

    private long insertHold(AuthenticatedUser user, Prepared p) {
        HoldRequest request = p.request();
        long tripId = request.tripId();
        Instant now = clock.instant();
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(now.plus(HOLD_DURATION), ZoneOffset.UTC);

        // The lock on the row of the trip makes a hold wait for a trip that is being closed or changed, and the other way round.
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

        long bookingId = insertBooking(user, p, createdAt, expiresAt);
        bookings.insertItems(bookingId, p.lines(), p.seats(), p.coaches());

        int firstSegment = p.journey().fromStop();
        int endSegment = p.journey().toStop(); // exclusive
        List<Integer> segments = IntStream.range(firstSegment, endSegment).boxed().toList();

        if (!request.seats().isEmpty()) {
            // All the seats on all the segments, or the primary key refuses and the whole hold fails (ORA-00001).
            inventory.insertReservations(tripId, bookingId, request.seats(), firstSegment, endSegment, expiresAt);
        }
        // Coaches in coach order, segments in ascending order: the same order for every hold, so two holds do not deadlock.
        List<HoldRequest.StandingRequest> standing = request.standingPlaces().stream()
                .sorted((a, b) -> Long.compare(a.coachId(), b.coachId())).toList();
        for (HoldRequest.StandingRequest places : standing) {
            for (int segment : segments) {
                // A conditional update: it changes the counter only if the places are still there. No row changed means gone.
                if (!inventory.reserveStandingSegment(tripId, places.coachId(), segment, places.qty())) {
                    throw new StandingUnavailableException(places.coachId());
                }
            }
            inventory.insertStandingHold(bookingId, tripId, places.coachId(), firstSegment, endSegment - 1, places.qty());
        }

        // Announced after the commit (see AvailabilityEvents), with the next version number of the trip.
        events.seats(SeatEvent.SEAT_HELD, tripId, request.seats(), segments);
        for (HoldRequest.StandingRequest places : standing) {
            events.standing(tripId, places.coachId(), -places.qty(), segments);
        }
        audit.record(user.id(), "booking", bookingId, "HOLD", Map.of("tripId", tripId, "fromStop", firstSegment, "toStop", endSegment,
                "seats", request.seats().size(), "standing", standing.stream().mapToInt(HoldRequest.StandingRequest::qty).sum(), "amount", p.amount()));
        return bookingId;
    }

    /** A clash of the (random) reference is not a clash of seats: it is retried with a new reference. */
    private long insertBooking(AuthenticatedUser user, Prepared p, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        for (int attempt = 0; ; attempt++) {
            try {
                return bookings.insert(BookingReferences.next(), user.id(), user.email(), user.name(), p.request().tripId(), p.journey().fromStop(),
                        p.journey().toStop(), p.amount(), expiresAt, createdAt);
            } catch (DuplicateKeyException e) {
                if (attempt >= 5) {
                    throw e;
                }
            }
        }
    }

    // ---- after a refusal: which items were lost -----------------------------------------------------------------------

    private List<HoldConflictException.Lost> lostItems(Prepared p) {
        List<HoldConflictException.Lost> lost = new ArrayList<>();
        JourneyAvailability now = availability.availability(p.request().tripId(), p.request().fromStop(), p.request().toStop()).availability();
        Map<Long, SeatState> states = new LinkedHashMap<>();
        now.seats().forEach(seat -> states.put(seat.seatId(), seat.state()));
        for (long seatId : p.request().seats()) {
            if (states.get(seatId) != SeatState.AVAILABLE) {
                InventoryRepository.SeatRef seat = p.seats().get(seatId);
                lost.add(new HoldConflictException.Lost("SEAT", seatId, "seat " + seat.seatNumber() + " of coach " + seat.coachCode()));
            }
        }
        for (HoldRequest.StandingRequest places : p.request().standingPlaces()) {
            int remaining = now.standing().stream().filter(c -> c.coachId() == places.coachId()).mapToInt(c -> c.remaining()).findFirst().orElse(0);
            if (remaining < places.qty()) {
                lost.add(new HoldConflictException.Lost("STANDING", places.coachId(), "standing places of coach " + p.coaches().get(places.coachId()).code()));
            }
        }
        if (lost.isEmpty()) {
            // The places were free again by the time we looked (a hold that was released in between): a retry would work.
            p.request().seats().forEach(id -> lost.add(new HoldConflictException.Lost("SEAT", id, "seat " + p.seats().get(id).seatNumber() + " of coach " + p.seats().get(id).coachCode())));
            p.request().standingPlaces().forEach(s -> lost.add(new HoldConflictException.Lost("STANDING", s.coachId(), "standing places of coach " + p.coaches().get(s.coachId()).code())));
        }
        return lost;
    }
}
