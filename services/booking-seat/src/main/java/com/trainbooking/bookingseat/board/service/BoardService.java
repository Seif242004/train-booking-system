package com.trainbooking.bookingseat.board.service;

import com.trainbooking.bookingseat.board.model.BoardCoachStanding;
import com.trainbooking.bookingseat.board.model.BoardRangeException;
import com.trainbooking.bookingseat.board.model.BoardResponse;
import com.trainbooking.bookingseat.board.model.BoardSeat;
import com.trainbooking.bookingseat.board.model.BoardSummary;
import com.trainbooking.bookingseat.board.model.BoardTrip;
import com.trainbooking.bookingseat.board.model.BoardTripDetail;
import com.trainbooking.bookingseat.board.model.SeatCounts;
import com.trainbooking.bookingseat.board.model.TripState;
import com.trainbooking.bookingseat.board.repository.BoardRepository;
import com.trainbooking.bookingseat.board.repository.BoardRepository.StandingBookingRow;
import com.trainbooking.bookingseat.board.repository.BoardRepository.StandingRow;
import com.trainbooking.bookingseat.board.repository.BoardRepository.TripRow;
import com.trainbooking.bookingseat.common.CoachClass;
import com.trainbooking.bookingseat.common.TripNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The live trips board (admin, read-only). Every count here is a full-journey count (CLAUDE.md "Full-journey availability"): a seat is
 * <b>free</b> only if it is free on every segment, <b>confirmed</b> if a confirmed booking has it on any segment, <b>held</b> if it has no
 * confirmed booking but a running hold on any segment. A hold whose five minutes ran out but that the sweeper has not released yet still
 * counts as held (it blocks the seat, SRS 4.2); the clock is read only for the lifecycle of the trip.
 */
@Service
public class BoardService {

    /** At most this many trips in an answer, so it stays small whatever range is asked for. */
    static final int MAX_TRIPS = 300;
    /** The widest range accepted, in days. */
    static final int MAX_DAYS = 62;

    private static final Set<String> KNOWN_STATES = Set.of("UPCOMING", "DEPARTED", "ARRIVED", "CANCELLED", "FULL");

    private final BoardRepository repository;
    private final Clock clock;
    private final ZoneId zone;

    public BoardService(BoardRepository repository, Clock clock, @Value("${trainbooking.timezone:Africa/Cairo}") String timezone) {
        this.repository = repository;
        this.clock = clock;
        this.zone = ZoneId.of(timezone);
    }

    /**
     * @param from  first local departure date (inclusive), default: 24 hours ago
     * @param to    last local departure date (inclusive), default: today + 7 days
     * @param state states to keep (UPCOMING, DEPARTED, ARRIVED, CANCELLED, FULL), empty = all; the summary always covers the whole range
     */
    @Transactional(readOnly = true)
    public BoardResponse board(String from, String to, String state) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        OffsetDateTime start = from == null || from.isBlank() ? now.minusSeconds(24 * 3600).atZone(zone).toOffsetDateTime()
                : parseDate("from", from).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = to == null || to.isBlank() ? today.plusDays(8).atStartOfDay(zone).toOffsetDateTime()
                : parseDate("to", to).plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        if (!end.isAfter(start)) {
            throw BoardRangeException.of("to", "must not be before from");
        }
        if (start.plusDays(MAX_DAYS).isBefore(end)) {
            throw BoardRangeException.of("to", "the range is at most " + MAX_DAYS + " days");
        }
        Set<String> wanted = parseStates(state);

        List<BoardTrip> all = build(repository.findTrips(start, end, MAX_TRIPS), now);
        BoardSummary summary = summarise(all);
        // FULL is a flag on upcoming trips, so asking for FULL keeps the trips with the flag.
        List<BoardTrip> shown = all.stream()
                .filter(t -> wanted.isEmpty() || wanted.contains(t.state().name()) || (t.full() && wanted.contains("FULL"))).toList();
        return new BoardResponse(now.atZone(zone).toOffsetDateTime(), start, end, summary, shown);
    }

    @Transactional(readOnly = true)
    public BoardTripDetail detail(long tripId) {
        Instant now = clock.instant();
        TripRow row = repository.findTrip(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        BoardTrip trip = build(List.of(row), now).get(0);
        List<BoardSeat> seats = repository.seatStates(tripId).stream()
                .map(s -> new BoardSeat(s.seatId(), s.coachId(), s.state(), "TAKEN".equals(s.state()) ? s.reference() : null)).toList();
        List<StandingBookingRow> bookings = repository.standingBookings(List.of(tripId));
        Map<Long, List<StandingRow>> byCoach = new TreeMap<>();
        repository.standingUsage(List.of(tripId)).forEach(u -> byCoach.computeIfAbsent(u.coachId(), id -> new ArrayList<>()).add(u));
        List<BoardCoachStanding> standing = new ArrayList<>();
        byCoach.forEach((coachId, usage) -> standing.add(new BoardCoachStanding(coachId, StandingBoardCounts.of(row.stops(), usage,
                bookings.stream().filter(b -> b.coachId() == coachId).toList()))));
        return new BoardTripDetail(now.atZone(zone).toOffsetDateTime(), trip, seats, standing);
    }

    private List<BoardTrip> build(List<TripRow> rows, Instant now) {
        List<Long> ids = rows.stream().map(TripRow::id).toList();
        Map<Long, Map<CoachClass, SeatCounts>> seats = new HashMap<>();
        repository.seatCounts(ids).forEach(r -> seats.computeIfAbsent(r.tripId(), k -> new HashMap<>()).put(r.coachClass(), r.counts()));
        Map<Long, Map<Long, List<StandingRow>>> usage = new HashMap<>();
        repository.standingUsage(ids).forEach(u -> usage.computeIfAbsent(u.tripId(), k -> new TreeMap<>())
                .computeIfAbsent(u.coachId(), k -> new ArrayList<>()).add(u));
        Map<Long, List<StandingBookingRow>> bookings = new HashMap<>();
        repository.standingBookings(ids).forEach(b -> bookings.computeIfAbsent(b.tripId(), k -> new ArrayList<>()).add(b));

        List<BoardTrip> trips = new ArrayList<>();
        for (TripRow row : rows) {
            Map<CoachClass, SeatCounts> perClass = seats.getOrDefault(row.id(), Map.of());
            SeatCounts standing = SeatCounts.NONE;
            for (List<StandingRow> coachRows : usage.getOrDefault(row.id(), Map.of()).values()) {
                long coachId = coachRows.get(0).coachId();
                List<StandingBookingRow> coachBookings = bookings.getOrDefault(row.id(), List.of()).stream().filter(b -> b.coachId() == coachId).toList();
                standing = standing.plus(StandingBoardCounts.of(row.stops(), coachRows, coachBookings));
            }
            SeatCounts first = perClass.getOrDefault(CoachClass.FIRST, SeatCounts.NONE);
            SeatCounts second = perClass.getOrDefault(CoachClass.SECOND, SeatCounts.NONE);
            SeatCounts third = perClass.getOrDefault(CoachClass.THIRD, SeatCounts.NONE);
            SeatCounts totals = first.plus(second).plus(third).plus(standing);
            TripState state = TripLifecycle.of(row.status(), row.departure().toInstant(), row.arrival().toInstant(), now);
            boolean left = state == TripState.DEPARTED || state == TripState.ARRIVED;
            // Shown in the railway's own time zone whatever the database session zone is (as the rest of this service does).
            OffsetDateTime departure = row.departure().atZoneSameInstant(zone).toOffsetDateTime();
            OffsetDateTime arrival = row.arrival().atZoneSameInstant(zone).toOffsetDateTime();
            trips.add(new BoardTrip(row.id(), row.trainNumber(), row.trainName(), row.routeName(), row.origin(), row.destination(),
                    departure, arrival, row.status(), state, TripLifecycle.isFull(state, totals),
                    first, second, third, standing, totals,
                    left ? departure : null, left ? totals.capacity() : null, left ? totals.confirmed() : null));
        }
        return trips;
    }

    private static BoardSummary summarise(List<BoardTrip> trips) {
        int upcoming = 0;
        int departed = 0;
        int arrived = 0;
        int full = 0;
        int cancelled = 0;
        for (BoardTrip trip : trips) {
            switch (trip.state()) {
                case UPCOMING -> upcoming++;
                case DEPARTED -> departed++;
                case ARRIVED -> arrived++;
                case CANCELLED -> cancelled++;
            }
            if (trip.full()) {
                full++;
            }
        }
        return new BoardSummary(trips.size(), upcoming, departed, arrived, full, cancelled);
    }

    private static Set<String> parseStates(String state) {
        if (state == null || state.isBlank()) {
            return Set.of();
        }
        Set<String> wanted = new HashSet<>();
        for (String part : state.split(",")) {
            String name = part.trim().toUpperCase();
            if (!KNOWN_STATES.contains(name)) {
                throw BoardRangeException.of("state", "must be a list of UPCOMING, DEPARTED, ARRIVED, CANCELLED or FULL");
            }
            wanted.add(name);
        }
        return wanted;
    }

    private static LocalDate parseDate(String field, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw BoardRangeException.of(field, "must be a date like 2026-11-04");
        }
    }
}
