package com.trainbooking.bookingseat.snapshot.repository;

import com.trainbooking.bookingseat.common.CoachClass;
import com.trainbooking.bookingseat.snapshot.model.TripSnapshot;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Data access for the copy of a trip. Everything is written with {@code MERGE}, so applying the same snapshot twice gives
 * the same rows, and rows that are no longer in the snapshot are deleted by comparing the keys in Java (an IN list is
 * limited to 1000 items in Oracle, and a train can have more seats than that). Parameterised SQL only. All methods run
 * in the transaction of the caller.
 */
@Repository
public class SnapshotRepository {

    /** How many rows of each kind the copy of a trip has. */
    public record Counts(int stops, int coaches, int seats, int fares, int standingCounters) {
    }

    /** What stays fixed once a trip has holds or bookings: the date, the train, the stops with their times, and the fares. */
    public record FrozenFacts(LocalDate departureDate, String trainNumber, Map<Integer, StopFact> stops, Map<String, BigDecimal> fares) {
    }

    public record StopFact(long stationId, Instant arrival, Instant departure) {
    }

    /** A seat as stored: the coach it is in, its number (for messages) and its class. */
    public record StoredSeat(long coachId, String seatNumber, CoachClass coachClass) {
    }

    /** A coach as stored: its code (for messages) and its class. */
    public record StoredCoach(String code, CoachClass coachClass) {
    }

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public SnapshotRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /**
     * Locks the row of the trip until the end of the transaction, so two pushes for the same trip are applied one after
     * the other. Returns the status of the trip, or empty if Booking has no copy of it yet.
     */
    public Optional<String> lockTrip(long tripId) {
        return jdbc.sql("SELECT status FROM trip WHERE id = :id FOR UPDATE").param("id", tripId).query(String.class).optional();
    }

    public Counts counts(long tripId) {
        return jdbc.sql("SELECT (SELECT COUNT(*) FROM trip_stop WHERE trip_id = :t) AS stops, "
                        + "(SELECT COUNT(*) FROM trip_coach WHERE trip_id = :t) AS coaches, "
                        + "(SELECT COUNT(*) FROM trip_seat WHERE trip_id = :t) AS seats, "
                        + "(SELECT COUNT(*) FROM trip_fare WHERE trip_id = :t) AS fares, "
                        + "(SELECT COUNT(*) FROM standing_usage WHERE trip_id = :t) AS standing FROM dual")
                .param("t", tripId)
                .query((rs, row) -> new Counts(rs.getInt("stops"), rs.getInt("coaches"), rs.getInt("seats"), rs.getInt("fares"), rs.getInt("standing")))
                .single();
    }

    /** True if any seat is held or booked on any segment, or any standing place is in use. */
    public boolean hasReservations(long tripId) {
        return jdbc.sql("SELECT CASE WHEN EXISTS (SELECT 1 FROM seat_reservation WHERE trip_id = :t) "
                        + "OR EXISTS (SELECT 1 FROM standing_usage WHERE trip_id = :t AND used > 0) THEN 1 ELSE 0 END FROM dual")
                .param("t", tripId).query(Integer.class).single() == 1;
    }

    public Set<Long> findReservedSeatIds(long tripId) {
        return new HashSet<>(jdbc.sql("SELECT DISTINCT seat_id FROM seat_reservation WHERE trip_id = :t").param("t", tripId).query(Long.class).list());
    }

    /** The most standing places in use on any segment, per coach, for the coaches that have any in use. */
    public Map<Long, Integer> findMaxStandingUsed(long tripId) {
        Map<Long, Integer> used = new HashMap<>();
        jdbc.sql("SELECT coach_id, MAX(used) AS max_used FROM standing_usage WHERE trip_id = :t GROUP BY coach_id HAVING MAX(used) > 0")
                .param("t", tripId)
                .query((rs, row) -> used.put(rs.getLong("coach_id"), rs.getInt("max_used")))
                .list();
        return used;
    }

    public Map<Long, StoredSeat> findSeats(long tripId) {
        Map<Long, StoredSeat> seats = new HashMap<>();
        jdbc.sql("SELECT seat_id, coach_id, seat_number, class FROM trip_seat WHERE trip_id = :t").param("t", tripId)
                .query((rs, row) -> seats.put(rs.getLong("seat_id"), new StoredSeat(rs.getLong("coach_id"), rs.getString("seat_number"),
                        CoachClass.valueOf(rs.getString("class")))))
                .list();
        return seats;
    }

    public Map<Long, StoredCoach> findCoaches(long tripId) {
        Map<Long, StoredCoach> coaches = new HashMap<>();
        jdbc.sql("SELECT coach_id, code, class FROM trip_coach WHERE trip_id = :t").param("t", tripId)
                .query((rs, row) -> coaches.put(rs.getLong("coach_id"), new StoredCoach(rs.getString("code"), CoachClass.valueOf(rs.getString("class"))))).list();
        return coaches;
    }

    public FrozenFacts readFrozenFacts(long tripId) {
        var header = jdbc.sql("SELECT departure_date, train_number FROM trip WHERE id = :t").param("t", tripId)
                .query((rs, row) -> Map.entry(rs.getObject("departure_date", LocalDate.class), rs.getString("train_number"))).single();
        Map<Integer, StopFact> stops = new LinkedHashMap<>();
        jdbc.sql("SELECT stop_index, station_id, arrival_time, departure_time FROM trip_stop WHERE trip_id = :t ORDER BY stop_index").param("t", tripId)
                .query((rs, row) -> stops.put(rs.getInt("stop_index"), new StopFact(rs.getLong("station_id"),
                        instant(rs.getObject("arrival_time", OffsetDateTime.class)), instant(rs.getObject("departure_time", OffsetDateTime.class)))))
                .list();
        Map<String, BigDecimal> fares = new HashMap<>();
        jdbc.sql("SELECT segment_index, class, amount FROM trip_fare WHERE trip_id = :t").param("t", tripId)
                .query((rs, row) -> fares.put(rs.getInt("segment_index") + "/" + rs.getString("class"), rs.getBigDecimal("amount"))).list();
        return new FrozenFacts(header.getKey(), header.getValue(), stops, fares);
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    // ---- writing: each method makes the stored rows equal to the snapshot for its table ----------------------------------

    /**
     * Inserts the trip or updates it. The status is deliberately not touched on update: it is OPEN for a new trip and is
     * changed only by the trip cancellation (slice 12), never by a snapshot.
     */
    public void mergeTrip(long tripId, TripSnapshot s) {
        jdbc.sql("MERGE INTO trip t USING (SELECT :id AS id FROM dual) src ON (t.id = src.id) "
                        + "WHEN MATCHED THEN UPDATE SET t.departure_date = :date, t.train_number = :trainNumber, t.train_name = :trainName, "
                        + "t.train_type = :trainType, t.route_name = :routeName, t.updated_at = SYSTIMESTAMP "
                        + "WHEN NOT MATCHED THEN INSERT (id, departure_date, train_number, train_name, train_type, route_name) "
                        + "VALUES (:id, :date, :trainNumber, :trainName, :trainType, :routeName)")
                .param("id", tripId)
                .param("date", s.departureDate())
                .param("trainNumber", s.train().number())
                .param("trainName", s.train().name())
                .param("trainType", s.train().type())
                .param("routeName", s.routeName())
                .update();
    }

    public void replaceStops(long tripId, List<TripSnapshot.Stop> stops) {
        List<SqlParameterSource> rows = new ArrayList<>();
        for (TripSnapshot.Stop stop : stops) {
            rows.add(new MapSqlParameterSource().addValue("t", tripId).addValue("idx", stop.stopIndex())
                    .addValue("station", stop.stationId()).addValue("code", stop.stationCode()).addValue("name", stop.stationName())
                    // The SQL type is given so that an empty time (first or last stop) is bound as a timestamp NULL.
                    .addValue("arrival", stop.arrivalTime(), Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("departure", stop.departureTime(), Types.TIMESTAMP_WITH_TIMEZONE));
        }
        batch.batchUpdate("MERGE INTO trip_stop x USING (SELECT :t AS trip_id, :idx AS stop_index FROM dual) src "
                + "ON (x.trip_id = src.trip_id AND x.stop_index = src.stop_index) "
                + "WHEN MATCHED THEN UPDATE SET x.station_id = :station, x.station_code = :code, x.station_name = :name, "
                + "x.arrival_time = :arrival, x.departure_time = :departure "
                + "WHEN NOT MATCHED THEN INSERT (trip_id, stop_index, station_id, station_code, station_name, arrival_time, departure_time) "
                + "VALUES (:t, :idx, :station, :code, :name, :arrival, :departure)", rows.toArray(SqlParameterSource[]::new));
        deleteRowsNotIn("SELECT stop_index FROM trip_stop WHERE trip_id = :t", tripId, keysOf(stops, s -> (long) s.stopIndex()),
                "DELETE FROM trip_stop WHERE trip_id = :t AND stop_index = :key");
    }

    /** Inserts and updates the coaches only. Coaches that are gone are deleted later, after their seats (see deleteCoachesNotIn). */
    public void mergeCoaches(long tripId, List<TripSnapshot.Coach> coaches) {
        List<SqlParameterSource> rows = new ArrayList<>();
        for (TripSnapshot.Coach coach : coaches) {
            rows.add(new MapSqlParameterSource().addValue("t", tripId).addValue("c", coach.id()).addValue("code", coach.code())
                    .addValue("class", coach.coachClass().name()).addValue("pos", coach.position()));
        }
        batch.batchUpdate("MERGE INTO trip_coach x USING (SELECT :t AS trip_id, :c AS coach_id FROM dual) src "
                + "ON (x.trip_id = src.trip_id AND x.coach_id = src.coach_id) "
                + "WHEN MATCHED THEN UPDATE SET x.code = :code, x.class = :class, x.position = :pos "
                + "WHEN NOT MATCHED THEN INSERT (trip_id, coach_id, code, class, position) VALUES (:t, :c, :code, :class, :pos)",
                rows.toArray(SqlParameterSource[]::new));
    }

    /** Inserts and updates the seats, then deletes the seats that are not in the snapshot any more. */
    public void replaceSeats(long tripId, List<TripSnapshot.Seat> seats) {
        List<SqlParameterSource> rows = new ArrayList<>();
        for (TripSnapshot.Seat seat : seats) {
            rows.add(new MapSqlParameterSource().addValue("t", tripId).addValue("s", seat.id()).addValue("c", seat.coachId())
                    .addValue("number", seat.seatNumber()).addValue("row", seat.row()).addValue("col", seat.column())
                    .addValue("class", seat.coachClass().name()));
        }
        batch.batchUpdate("MERGE INTO trip_seat x USING (SELECT :t AS trip_id, :s AS seat_id FROM dual) src "
                + "ON (x.trip_id = src.trip_id AND x.seat_id = src.seat_id) "
                + "WHEN MATCHED THEN UPDATE SET x.coach_id = :c, x.seat_number = :number, x.seat_row = :row, x.seat_column = :col, x.class = :class "
                + "WHEN NOT MATCHED THEN INSERT (trip_id, seat_id, coach_id, seat_number, seat_row, seat_column, class) "
                + "VALUES (:t, :s, :c, :number, :row, :col, :class)", rows.toArray(SqlParameterSource[]::new));
        // A seat that has reservations cannot be deleted: the foreign key of seat_reservation refuses it (ORA-02292).
        deleteRowsNotIn("SELECT seat_id FROM trip_seat WHERE trip_id = :t", tripId, keysOf(seats, TripSnapshot.Seat::id),
                "DELETE FROM trip_seat WHERE trip_id = :t AND seat_id = :key");
    }

    /** Deletes the coaches that are not in the snapshot. Their seats were deleted before by replaceSeats. */
    public void deleteCoachesNotIn(long tripId, List<TripSnapshot.Coach> coaches) {
        deleteRowsNotIn("SELECT coach_id FROM trip_coach WHERE trip_id = :t", tripId, keysOf(coaches, TripSnapshot.Coach::id),
                "DELETE FROM trip_coach WHERE trip_id = :t AND coach_id = :key");
    }

    /**
     * Makes standing_usage match the capacities of the coaches: one counter per coach with standing places and per
     * segment. A counter that exists keeps its {@code used} and gets the new capacity (the CHECK used &lt;= capacity
     * refuses a capacity below the places in use); a new counter starts at 0; a counter of a coach without standing
     * places is deleted.
     */
    public void replaceStanding(long tripId, List<TripSnapshot.Coach> coaches, int segments) {
        List<SqlParameterSource> rows = new ArrayList<>();
        Set<String> wanted = new HashSet<>();
        for (TripSnapshot.Coach coach : coaches) {
            if (coach.standingCapacity() == 0) {
                continue;
            }
            for (int segment = 0; segment < segments; segment++) {
                wanted.add(coach.id() + "/" + segment);
                rows.add(new MapSqlParameterSource().addValue("t", tripId).addValue("c", coach.id()).addValue("seg", segment)
                        .addValue("cap", coach.standingCapacity()));
            }
        }
        batch.batchUpdate("MERGE INTO standing_usage x USING (SELECT :t AS trip_id, :c AS coach_id, :seg AS segment_index FROM dual) src "
                + "ON (x.trip_id = src.trip_id AND x.coach_id = src.coach_id AND x.segment_index = src.segment_index) "
                + "WHEN MATCHED THEN UPDATE SET x.capacity = :cap "
                + "WHEN NOT MATCHED THEN INSERT (trip_id, coach_id, segment_index, capacity, used) VALUES (:t, :c, :seg, :cap, 0)",
                rows.toArray(SqlParameterSource[]::new));

        List<SqlParameterSource> stale = new ArrayList<>();
        jdbc.sql("SELECT coach_id, segment_index FROM standing_usage WHERE trip_id = :t").param("t", tripId)
                .query((rs, row) -> {
                    if (!wanted.contains(rs.getLong("coach_id") + "/" + rs.getInt("segment_index"))) {
                        stale.add(new MapSqlParameterSource().addValue("t", tripId).addValue("c", rs.getLong("coach_id"))
                                .addValue("seg", rs.getInt("segment_index")));
                    }
                    return null;
                }).list();
        if (!stale.isEmpty()) {
            batch.batchUpdate("DELETE FROM standing_usage WHERE trip_id = :t AND coach_id = :c AND segment_index = :seg",
                    stale.toArray(SqlParameterSource[]::new));
        }
    }

    public void replaceFares(long tripId, List<TripSnapshot.Fare> fares) {
        List<SqlParameterSource> rows = new ArrayList<>();
        Set<String> wanted = new HashSet<>();
        for (TripSnapshot.Fare fare : fares) {
            wanted.add(fare.segmentIndex() + "/" + fare.coachClass());
            rows.add(new MapSqlParameterSource().addValue("t", tripId).addValue("seg", fare.segmentIndex())
                    .addValue("class", fare.coachClass().name()).addValue("amount", fare.amount()).addValue("currency", fare.currency()));
        }
        batch.batchUpdate("MERGE INTO trip_fare x USING (SELECT :t AS trip_id, :seg AS segment_index, :class AS class FROM dual) src "
                + "ON (x.trip_id = src.trip_id AND x.segment_index = src.segment_index AND x.class = src.class) "
                + "WHEN MATCHED THEN UPDATE SET x.amount = :amount, x.currency = :currency "
                + "WHEN NOT MATCHED THEN INSERT (trip_id, segment_index, class, amount, currency) VALUES (:t, :seg, :class, :amount, :currency)",
                rows.toArray(SqlParameterSource[]::new));

        List<SqlParameterSource> stale = new ArrayList<>();
        jdbc.sql("SELECT segment_index, class FROM trip_fare WHERE trip_id = :t").param("t", tripId)
                .query((rs, row) -> {
                    if (!wanted.contains(rs.getInt("segment_index") + "/" + CoachClass.valueOf(rs.getString("class")))) {
                        stale.add(new MapSqlParameterSource().addValue("t", tripId).addValue("seg", rs.getInt("segment_index"))
                                .addValue("class", rs.getString("class")));
                    }
                    return null;
                }).list();
        if (!stale.isEmpty()) {
            batch.batchUpdate("DELETE FROM trip_fare WHERE trip_id = :t AND segment_index = :seg AND class = :class",
                    stale.toArray(SqlParameterSource[]::new));
        }
    }

    /** Deletes the trip; the stops, coaches, seats, fares and counters go with it (ON DELETE CASCADE). */
    public void deleteTrip(long tripId) {
        jdbc.sql("DELETE FROM trip WHERE id = :t").param("t", tripId).update();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private static <T> Set<Long> keysOf(List<T> items, Function<T, Long> key) {
        Set<Long> keys = new HashSet<>();
        items.forEach(item -> keys.add(key.apply(item)));
        return keys;
    }

    /** Deletes, in one batch, the rows whose key is stored but is not in {@code keep}. */
    private void deleteRowsNotIn(String selectKeys, long tripId, Set<Long> keep, String deleteOne) {
        List<SqlParameterSource> stale = new ArrayList<>();
        jdbc.sql(selectKeys).param("t", tripId).query((rs, row) -> {
            long key = rs.getLong(1);
            if (!keep.contains(key)) {
                stale.add(new MapSqlParameterSource().addValue("t", tripId).addValue("key", key));
            }
            return null;
        }).list();
        if (!stale.isEmpty()) {
            batch.batchUpdate(deleteOne, stale.toArray(SqlParameterSource[]::new));
        }
    }
}
