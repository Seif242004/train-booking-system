package com.trainbooking.bookingseat.testsupport;

import com.trainbooking.bookingseat.common.CoachClass;
import com.trainbooking.bookingseat.snapshot.model.TripSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A valid snapshot to start from. It is the same trip as the SQL fixture of {@link DatabaseTest}: 4 stops (segments 0 to
 * 2); coach 10 FIRST with seat 1; coach 20 SECOND with seats 2 and 3 and 10 standing places; coach 30 THIRD with seat 4 and
 * 5 standing places; a fare for every segment and class. Tests change one thing with the {@code with...} helpers.
 */
public final class Snapshots {

    private Snapshots() {
    }

    public static TripSnapshot valid() {
        OffsetDateTime day = OffsetDateTime.parse("2026-11-02T08:00:00+02:00");
        List<TripSnapshot.Stop> stops = List.of(
                new TripSnapshot.Stop(0, 100L, "CAI", "Cairo", null, day),
                new TripSnapshot.Stop(1, 101L, "GIZ", "Giza", day.plusMinutes(40), day.plusMinutes(45)),
                new TripSnapshot.Stop(2, 102L, "TAN", "Tanta", day.plusMinutes(100), day.plusMinutes(105)),
                new TripSnapshot.Stop(3, 103L, "ALX", "Alexandria", day.plusMinutes(165), null));
        List<TripSnapshot.Coach> coaches = List.of(
                new TripSnapshot.Coach(10L, "A", CoachClass.FIRST, 1, 0),
                new TripSnapshot.Coach(20L, "B", CoachClass.SECOND, 2, 10),
                new TripSnapshot.Coach(30L, "C", CoachClass.THIRD, 3, 5));
        List<TripSnapshot.Seat> seats = List.of(
                new TripSnapshot.Seat(1L, 10L, "A1", 1, "A", CoachClass.FIRST),
                new TripSnapshot.Seat(2L, 20L, "B1", 1, "A", CoachClass.SECOND),
                new TripSnapshot.Seat(3L, 20L, "B2", 1, "B", CoachClass.SECOND),
                new TripSnapshot.Seat(4L, 30L, "C1", 1, "A", CoachClass.THIRD));
        List<TripSnapshot.Fare> fares = new ArrayList<>();
        for (int segment = 0; segment < 3; segment++) {
            fares.add(new TripSnapshot.Fare(segment, CoachClass.FIRST, new BigDecimal("80.00"), "EGP"));
            fares.add(new TripSnapshot.Fare(segment, CoachClass.SECOND, new BigDecimal("50.00"), "EGP"));
            fares.add(new TripSnapshot.Fare(segment, CoachClass.THIRD, new BigDecimal("30.00"), "EGP"));
        }
        return new TripSnapshot(LocalDate.of(2026, 11, 2), new TripSnapshot.Train("T100", "Cairo-Alexandria Express", "Express"),
                "Cairo-Alexandria", stops, coaches, seats, fares);
    }

    public static TripSnapshot withStops(TripSnapshot s, List<TripSnapshot.Stop> stops) {
        return new TripSnapshot(s.departureDate(), s.train(), s.routeName(), stops, s.coaches(), s.seats(), s.fares());
    }

    public static TripSnapshot withCoaches(TripSnapshot s, List<TripSnapshot.Coach> coaches) {
        return new TripSnapshot(s.departureDate(), s.train(), s.routeName(), s.stops(), coaches, s.seats(), s.fares());
    }

    public static TripSnapshot withSeats(TripSnapshot s, List<TripSnapshot.Seat> seats) {
        return new TripSnapshot(s.departureDate(), s.train(), s.routeName(), s.stops(), s.coaches(), seats, s.fares());
    }

    public static TripSnapshot withFares(TripSnapshot s, List<TripSnapshot.Fare> fares) {
        return new TripSnapshot(s.departureDate(), s.train(), s.routeName(), s.stops(), s.coaches(), s.seats(), fares);
    }

    public static TripSnapshot withDate(TripSnapshot s, LocalDate date) {
        return new TripSnapshot(date, s.train(), s.routeName(), s.stops(), s.coaches(), s.seats(), s.fares());
    }

    /** Replaces the list element at {@code index} with the result of {@code change}. */
    public static <T> List<T> replace(List<T> list, int index, UnaryOperator<T> change) {
        List<T> copy = new ArrayList<>(list);
        copy.set(index, change.apply(copy.get(index)));
        return copy;
    }

    public static <T> List<T> without(List<T> list, int index) {
        List<T> copy = new ArrayList<>(list);
        copy.remove(index);
        return copy;
    }
}
