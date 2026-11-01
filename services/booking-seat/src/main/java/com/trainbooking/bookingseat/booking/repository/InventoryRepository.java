package com.trainbooking.bookingseat.booking.repository;

import com.trainbooking.bookingseat.common.CoachClass;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The inventory of a trip: the seat reservations, the standing counters, the standing holds and the change counter of the
 * trip (SRS section 5). Parameterised SQL only. Every method runs in the transaction of the caller; the double-booking
 * guarantee is the primary key of seat_reservation, the standing guarantee is the conditional update (CLAUDE.md, "Rules
 * that must not be broken").
 */
@Repository
public class InventoryRepository {

    /** A seat of the copy of the trip, with the coach it is in. */
    public record SeatRef(long seatId, String seatNumber, long coachId, String coachCode, CoachClass coachClass) {
    }

    /** A coach of the copy of the trip, and whether it has standing counters. */
    public record CoachRef(long coachId, String code, CoachClass coachClass, boolean hasStanding) {
    }

    /** The standing places of a booking in one coach, on the segments {@code fromSegment} to {@code toSegment}. */
    public record StandingHoldRow(long coachId, int fromSegment, int toSegment, int qty) {
    }

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public InventoryRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    // ---- the trip -----------------------------------------------------------------------------------------------

    /**
     * Locks the row of the trip until the end of the transaction and returns its status. Holds, the cancellation of a trip and
     * the snapshot receiver all take this lock, so a hold can never slip in while a trip is being closed, and a snapshot is
     * never applied in the middle of a hold.
     */
    public Optional<String> lockTrip(long tripId) {
        return jdbc.sql("SELECT status FROM trip WHERE id = :id FOR UPDATE").param("id", tripId).query(String.class).optional();
    }

    public Optional<OffsetDateTime> departureTime(long tripId, int stopIndex) {
        return jdbc.sql("SELECT departure_time FROM trip_stop WHERE trip_id = :t AND stop_index = :i").param("t", tripId).param("i", stopIndex)
                .query((rs, row) -> rs.getObject("departure_time", OffsetDateTime.class)).optional().map(time -> time);
    }

    public Map<Long, SeatRef> findSeats(long tripId, Collection<Long> seatIds) {
        Map<Long, SeatRef> seats = new HashMap<>();
        if (seatIds.isEmpty()) {
            return seats;
        }
        jdbc.sql("SELECT s.seat_id, s.seat_number, s.coach_id, c.code, s.class FROM trip_seat s "
                        + "JOIN trip_coach c ON c.trip_id = s.trip_id AND c.coach_id = s.coach_id "
                        + "WHERE s.trip_id = :t AND s.seat_id IN (:ids)")
                .param("t", tripId).param("ids", seatIds)
                .query((rs, row) -> seats.put(rs.getLong("seat_id"), new SeatRef(rs.getLong("seat_id"), rs.getString("seat_number"),
                        rs.getLong("coach_id"), rs.getString("code"), CoachClass.valueOf(rs.getString("class")))))
                .list();
        return seats;
    }

    public Map<Long, CoachRef> findCoaches(long tripId, Collection<Long> coachIds) {
        Map<Long, CoachRef> coaches = new HashMap<>();
        if (coachIds.isEmpty()) {
            return coaches;
        }
        jdbc.sql("SELECT c.coach_id, c.code, c.class, "
                        + "(SELECT COUNT(*) FROM standing_usage u WHERE u.trip_id = c.trip_id AND u.coach_id = c.coach_id) AS counters "
                        + "FROM trip_coach c WHERE c.trip_id = :t AND c.coach_id IN (:ids)")
                .param("t", tripId).param("ids", coachIds)
                .query((rs, row) -> coaches.put(rs.getLong("coach_id"), new CoachRef(rs.getLong("coach_id"), rs.getString("code"),
                        CoachClass.valueOf(rs.getString("class")), rs.getInt("counters") > 0)))
                .list();
        return coaches;
    }

    // ---- seats ------------------------------------------------------------------------------------------------------

    /**
     * Reserves the seats on every segment {@code fromSegment} to {@code toSegment - 1} as one JDBC batch, in sorted
     * (seat, segment) order so that two holds never lock in opposite orders (deadlocks, CLAUDE.md). A seat that is already
     * held or booked on any of the segments, even a hold that has run out but has not been released yet, violates the primary
     * key (ORA-00001, a DuplicateKeyException) and the whole hold fails. Nothing expired is purged here (SRS 4.2).
     */
    public void insertReservations(long tripId, long bookingId, Collection<Long> seatIds, int fromSegment, int toSegment, OffsetDateTime expiresAt) {
        List<Long> sorted = seatIds.stream().sorted().toList();
        List<SqlParameterSource> rows = new ArrayList<>();
        for (long seatId : sorted) {
            for (int segment = fromSegment; segment < toSegment; segment++) {
                rows.add(new MapSqlParameterSource().addValue("t", tripId).addValue("s", seatId).addValue("seg", segment)
                        .addValue("b", bookingId).addValue("exp", expiresAt));
            }
        }
        batch.batchUpdate("INSERT INTO seat_reservation (trip_id, seat_id, segment_index, booking_id, expires_at) VALUES (:t, :s, :seg, :b, :exp)",
                rows.toArray(SqlParameterSource[]::new));
    }

    public List<Long> findReservedSeatIds(long bookingId) {
        return jdbc.sql("SELECT DISTINCT seat_id FROM seat_reservation WHERE booking_id = :b ORDER BY seat_id").param("b", bookingId).query(Long.class).list();
    }

    /** The segments a booking holds seats on (the same for all its seats). */
    public List<Integer> findReservedSegments(long bookingId) {
        return jdbc.sql("SELECT DISTINCT segment_index FROM seat_reservation WHERE booking_id = :b ORDER BY segment_index").param("b", bookingId).query(Integer.class).list();
    }

    /** Gives back one seat of a booking on every segment it was reserved on, and returns those segments. */
    public List<Integer> deleteSeatReservations(long bookingId, long seatId) {
        List<Integer> segments = jdbc.sql("SELECT segment_index FROM seat_reservation WHERE booking_id = :b AND seat_id = :s ORDER BY segment_index")
                .param("b", bookingId).param("s", seatId).query(Integer.class).list();
        jdbc.sql("DELETE FROM seat_reservation WHERE booking_id = :b AND seat_id = :s").param("b", bookingId).param("s", seatId).update();
        return segments;
    }

    public int deleteReservations(long bookingId) {
        return jdbc.sql("DELETE FROM seat_reservation WHERE booking_id = :b").param("b", bookingId).update();
    }

    /** A paid booking: the reservations stop being holds (no expiry) and become bookings. */
    public int confirmReservations(long bookingId) {
        return jdbc.sql("UPDATE seat_reservation SET expires_at = NULL WHERE booking_id = :b").param("b", bookingId).update();
    }

    // ---- standing ------------------------------------------------------------------------------------------------------

    /**
     * Takes {@code qty} places of one coach on one segment with a conditional update: the row is changed only if the places
     * are still there. The caller calls it for the segments in ascending order and treats {@code false} as "gone" (it rolls
     * the whole hold back).
     */
    public boolean reserveStandingSegment(long tripId, long coachId, int segment, int qty) {
        return jdbc.sql("UPDATE standing_usage SET used = used + :qty WHERE trip_id = :t AND coach_id = :c AND segment_index = :seg AND used + :qty <= capacity")
                .param("qty", qty).param("t", tripId).param("c", coachId).param("seg", segment).update() == 1;
    }

    public void releaseStandingSegment(long tripId, long coachId, int segment, int qty) {
        int updated = jdbc.sql("UPDATE standing_usage SET used = used - :qty WHERE trip_id = :t AND coach_id = :c AND segment_index = :seg AND used >= :qty")
                .param("qty", qty).param("t", tripId).param("c", coachId).param("seg", segment).update();
        if (updated != 1) {
            throw new IllegalStateException("The standing counter of coach " + coachId + " segment " + segment + " does not hold " + qty + " places");
        }
    }

    public void insertStandingHold(long bookingId, long tripId, long coachId, int fromSegment, int toSegment, int qty) {
        jdbc.sql("INSERT INTO standing_hold (booking_id, trip_id, coach_id, from_segment, to_segment, qty) VALUES (:b, :t, :c, :f, :to, :q)")
                .param("b", bookingId).param("t", tripId).param("c", coachId).param("f", fromSegment).param("to", toSegment).param("q", qty).update();
    }

    public Optional<StandingHoldRow> findStandingHold(long bookingId, long coachId) {
        return jdbc.sql("SELECT coach_id, from_segment, to_segment, qty FROM standing_hold WHERE booking_id = :b AND coach_id = :c").param("b", bookingId).param("c", coachId)
                .query((rs, row) -> new StandingHoldRow(rs.getLong("coach_id"), rs.getInt("from_segment"), rs.getInt("to_segment"), rs.getInt("qty"))).optional();
    }

    public void setStandingHoldQty(long bookingId, long coachId, int qty) {
        jdbc.sql("UPDATE standing_hold SET qty = :q WHERE booking_id = :b AND coach_id = :c").param("q", qty).param("b", bookingId).param("c", coachId).update();
    }

    public void deleteStandingHold(long bookingId, long coachId) {
        jdbc.sql("DELETE FROM standing_hold WHERE booking_id = :b AND coach_id = :c").param("b", bookingId).param("c", coachId).update();
    }

    public List<StandingHoldRow> findStandingHolds(long bookingId) {
        return jdbc.sql("SELECT coach_id, from_segment, to_segment, qty FROM standing_hold WHERE booking_id = :b ORDER BY coach_id").param("b", bookingId)
                .query((rs, row) -> new StandingHoldRow(rs.getLong("coach_id"), rs.getInt("from_segment"), rs.getInt("to_segment"), rs.getInt("qty"))).list();
    }

    public void deleteStandingHolds(long bookingId) {
        jdbc.sql("DELETE FROM standing_hold WHERE booking_id = :b").param("b", bookingId).update();
    }

    // ---- the change counter of the trip ----------------------------------------------------------------------------

    /** Increases the counter of the trip and returns the new value. The update takes a row lock, so versions are never duplicated. */
    public long nextVersion(long tripId) {
        int updated = jdbc.sql("UPDATE trip_version SET version = version + 1 WHERE trip_id = :t").param("t", tripId).update();
        if (updated != 1) {
            throw new IllegalStateException("Trip " + tripId + " has no version counter");
        }
        return currentVersion(tripId);
    }

    public long currentVersion(long tripId) {
        return jdbc.sql("SELECT version FROM trip_version WHERE trip_id = :t").param("t", tripId).query(Long.class).optional().orElse(0L);
    }
}
