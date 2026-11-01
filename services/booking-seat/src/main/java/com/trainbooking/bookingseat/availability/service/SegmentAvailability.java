package com.trainbooking.bookingseat.availability.service;

import com.trainbooking.bookingseat.availability.model.CoachStandingLeft;
import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.availability.model.JourneyAvailability;
import com.trainbooking.bookingseat.availability.model.ReservationInfo;
import com.trainbooking.bookingseat.availability.model.SeatInfo;
import com.trainbooking.bookingseat.availability.model.SeatState;
import com.trainbooking.bookingseat.availability.model.SeatStatus;
import com.trainbooking.bookingseat.availability.model.StandingInfo;
import com.trainbooking.bookingseat.common.CoachClass;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The full-journey rule (SRS 4.1, CLAUDE.md "Rules that must not be broken"). It is plain Java with no database access,
 * so the rule can be tested on its own. A journey from stop i to stop j uses the segments i to j-1:
 * <ul>
 *   <li>a seat is available only if it is free on <b>every</b> one of those segments; a reservation on any other
 *       segment is ignored;</li>
 *   <li>standing places left in a coach are the <b>minimum</b> of (capacity - used) over those segments.</li>
 * </ul>
 * The rows that are passed in may cover the whole trip: this class applies the segment filter itself, so it does not
 * depend on the query that loaded them having done so.
 */
public final class SegmentAvailability {

    private SegmentAvailability() {
    }

    public static JourneyAvailability compute(Journey journey, List<SeatInfo> seats, List<ReservationInfo> reservations,
                                              List<StandingInfo> standingRows) {
        List<SeatStatus> statuses = seatStatuses(journey, seats, reservations);
        List<CoachStandingLeft> standing = standingPerCoach(journey, standingRows);
        return new JourneyAvailability(journey, statuses, standing, seatsLeftPerClass(statuses), standingLeftPerClass(standing));
    }

    /**
     * A seat is BOOKED if it is confirmed on any segment of the journey, otherwise HELD if it is held on any, otherwise
     * AVAILABLE. BOOKED wins over HELD because a confirmed booking is final while a hold may still be released.
     */
    private static List<SeatStatus> seatStatuses(Journey journey, List<SeatInfo> seats, List<ReservationInfo> reservations) {
        Map<Long, SeatState> taken = new HashMap<>();
        for (ReservationInfo reservation : reservations) {
            if (!journey.includes(reservation.segmentIndex())) {
                continue; // taken only outside the journey: does not matter (A to B and B to C can share a seat)
            }
            SeatState state = reservation.held() ? SeatState.HELD : SeatState.BOOKED;
            taken.merge(reservation.seatId(), state, (a, b) -> a == SeatState.BOOKED || b == SeatState.BOOKED ? SeatState.BOOKED : SeatState.HELD);
        }
        List<SeatStatus> statuses = new ArrayList<>();
        for (SeatInfo seat : seats) {
            statuses.add(new SeatStatus(seat.seatId(), seat.coachId(), seat.coachClass(), taken.getOrDefault(seat.seatId(), SeatState.AVAILABLE)));
        }
        return List.copyOf(statuses);
    }

    /** One entry per coach that has standing counters, ordered by coach id. */
    private static List<CoachStandingLeft> standingPerCoach(Journey journey, List<StandingInfo> rows) {
        Map<Long, List<StandingInfo>> byCoach = new TreeMap<>();
        for (StandingInfo row : rows) {
            byCoach.computeIfAbsent(row.coachId(), id -> new ArrayList<>()).add(row);
        }
        List<CoachStandingLeft> result = new ArrayList<>();
        for (List<StandingInfo> coachRows : byCoach.values()) {
            StandingInfo any = coachRows.get(0);
            int capacity = coachRows.stream().map(StandingInfo::capacity).max(Comparator.naturalOrder()).orElse(0);
            result.add(new CoachStandingLeft(any.coachId(), any.coachClass(), capacity, remainingForJourney(journey, coachRows)));
        }
        return List.copyOf(result);
    }

    /**
     * The minimum of (capacity - used) over the segments of the journey. A segment of the journey that has no counter
     * row counts as no capacity at all: a missing counter must never be read as "free". Never below zero.
     */
    private static int remainingForJourney(Journey journey, List<StandingInfo> coachRows) {
        Map<Integer, StandingInfo> bySegment = new HashMap<>();
        for (StandingInfo row : coachRows) {
            bySegment.put(row.segmentIndex(), row);
        }
        int minimum = Integer.MAX_VALUE;
        for (int segment = journey.fromStop(); segment < journey.toStop(); segment++) {
            StandingInfo row = bySegment.get(segment);
            int remaining = row == null ? 0 : row.capacity() - row.used();
            minimum = Math.min(minimum, remaining);
        }
        return Math.max(minimum, 0);
    }

    /** Available seats per class; a class is listed (even at 0) if the trip has seats of it. */
    private static Map<CoachClass, Integer> seatsLeftPerClass(List<SeatStatus> statuses) {
        Map<CoachClass, Integer> left = new EnumMap<>(CoachClass.class);
        for (SeatStatus seat : statuses) {
            left.merge(seat.coachClass(), seat.state() == SeatState.AVAILABLE ? 1 : 0, Integer::sum);
        }
        return left;
    }

    /**
     * Standing places per class: the sum over the coaches of the class of each coach's own remaining places. A standing
     * passenger books places in one coach, so what can be booked is what each coach has left for the whole journey. The
     * minimum over segments of the total of all coaches could be higher, but it could not all be booked.
     */
    private static Map<CoachClass, Integer> standingLeftPerClass(List<CoachStandingLeft> standing) {
        Map<CoachClass, Integer> left = new EnumMap<>(CoachClass.class);
        for (CoachStandingLeft coach : standing) {
            left.merge(coach.coachClass(), coach.remaining(), Integer::sum);
        }
        return left;
    }
}
