package com.trainbooking.bookingseat.snapshot.service;

import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.CoachClass;
import com.trainbooking.bookingseat.snapshot.model.TripSnapshot;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of a snapshot that involve more than one field. Bean Validation has already checked each field on its own.
 * A snapshot is applied as a whole or not at all, so every problem found here keeps all of it out of the database.
 */
public final class SnapshotValidator {

    private SnapshotValidator() {
    }

    /** @return every problem found, with the path of the field that causes it; empty if the snapshot is consistent */
    public static List<ApiError.Detail> check(TripSnapshot snapshot) {
        List<ApiError.Detail> problems = new ArrayList<>();
        checkStops(snapshot.stops(), problems);
        Map<Long, TripSnapshot.Coach> coachById = checkCoaches(snapshot.coaches(), problems);
        checkSeats(snapshot.seats(), coachById, problems);
        checkFares(snapshot, problems);
        return problems;
    }

    private static void checkStops(List<TripSnapshot.Stop> stops, List<ApiError.Detail> problems) {
        Set<Long> stations = new HashSet<>();
        for (int i = 0; i < stops.size(); i++) {
            TripSnapshot.Stop stop = stops.get(i);
            String at = "stops[" + i + "]";
            if (stop.stopIndex() != i) {
                problems.add(new ApiError.Detail(at + ".stopIndex", "must be " + i + ": the stops are numbered from 0, in order"));
            }
            if (!stations.add(stop.stationId())) {
                problems.add(new ApiError.Detail(at + ".stationId", "this station is already a stop of the trip"));
            }
            boolean first = i == 0;
            boolean last = i == stops.size() - 1;
            if (first && stop.arrivalTime() != null) {
                problems.add(new ApiError.Detail(at + ".arrivalTime", "must be empty at the first stop"));
            }
            if (!first && stop.arrivalTime() == null) {
                problems.add(new ApiError.Detail(at + ".arrivalTime", "is required"));
            }
            if (last && stop.departureTime() != null) {
                problems.add(new ApiError.Detail(at + ".departureTime", "must be empty at the last stop"));
            }
            if (!last && stop.departureTime() == null) {
                problems.add(new ApiError.Detail(at + ".departureTime", "is required"));
            }
        }
    }

    private static Map<Long, TripSnapshot.Coach> checkCoaches(List<TripSnapshot.Coach> coaches, List<ApiError.Detail> problems) {
        Map<Long, TripSnapshot.Coach> byId = new HashMap<>();
        Set<String> codes = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        for (int i = 0; i < coaches.size(); i++) {
            TripSnapshot.Coach coach = coaches.get(i);
            String at = "coaches[" + i + "]";
            if (byId.putIfAbsent(coach.id(), coach) != null) {
                problems.add(new ApiError.Detail(at + ".id", "duplicates another coach of the snapshot"));
            }
            if (!codes.add(coach.code())) {
                problems.add(new ApiError.Detail(at + ".code", "duplicates another coach of the snapshot"));
            }
            if (!positions.add(coach.position())) {
                problems.add(new ApiError.Detail(at + ".position", "duplicates another coach of the snapshot"));
            }
        }
        return byId;
    }

    private static void checkSeats(List<TripSnapshot.Seat> seats, Map<Long, TripSnapshot.Coach> coachById, List<ApiError.Detail> problems) {
        Set<Long> ids = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < seats.size(); i++) {
            TripSnapshot.Seat seat = seats.get(i);
            String at = "seats[" + i + "]";
            if (!ids.add(seat.id())) {
                problems.add(new ApiError.Detail(at + ".id", "duplicates another seat of the snapshot"));
            }
            TripSnapshot.Coach coach = coachById.get(seat.coachId());
            if (coach == null) {
                problems.add(new ApiError.Detail(at + ".coachId", "coach " + seat.coachId() + " is not in the snapshot"));
            } else if (coach.coachClass() != seat.coachClass()) {
                problems.add(new ApiError.Detail(at + ".class", "must be the class of its coach (" + coach.coachClass() + ")"));
            }
            if (!numbers.add(seat.coachId() + "/" + seat.seatNumber())) {
                problems.add(new ApiError.Detail(at + ".seatNumber", "duplicates another seat of the same coach"));
            }
        }
    }

    /**
     * A ticket must always be priceable, so every segment needs a fare for every class that the trip has a coach of
     * (Train and Schedule checks this before it creates a trip; here it is checked again, because Booking must never
     * hold a trip it cannot price).
     */
    private static void checkFares(TripSnapshot snapshot, List<ApiError.Detail> problems) {
        int segments = snapshot.stops().size() - 1;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < snapshot.fares().size(); i++) {
            TripSnapshot.Fare fare = snapshot.fares().get(i);
            String at = "fares[" + i + "]";
            if (fare.segmentIndex() >= segments) {
                problems.add(new ApiError.Detail(at + ".segmentIndex", "the trip has segments 0 to " + (segments - 1)));
            }
            if (!seen.add(fare.segmentIndex() + "/" + fare.coachClass())) {
                problems.add(new ApiError.Detail(at, "there is already a fare for this segment and class"));
            }
            if (fare.amount().signum() <= 0 || fare.amount().stripTrailingZeros().scale() > 2) {
                problems.add(new ApiError.Detail(at + ".amount", "must be above 0 with at most 2 decimals"));
            }
            if (!"EGP".equals(fare.currency())) {
                problems.add(new ApiError.Detail(at + ".currency", "must be EGP"));
            }
        }
        Set<CoachClass> classes = EnumSet.noneOf(CoachClass.class);
        snapshot.coaches().forEach(c -> classes.add(c.coachClass()));
        for (int segment = 0; segment < segments; segment++) {
            for (CoachClass coachClass : classes) {
                if (!seen.contains(segment + "/" + coachClass)) {
                    problems.add(new ApiError.Detail("fares", "segment " + segment + " has no " + coachClass + " fare"));
                }
            }
        }
    }
}
