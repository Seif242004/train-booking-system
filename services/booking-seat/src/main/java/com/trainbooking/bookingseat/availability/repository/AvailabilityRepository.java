package com.trainbooking.bookingseat.availability.repository;

import com.trainbooking.bookingseat.availability.model.FareInfo;
import com.trainbooking.bookingseat.availability.model.Journey;
import com.trainbooking.bookingseat.availability.model.ReservationInfo;
import com.trainbooking.bookingseat.availability.model.SeatInfo;
import com.trainbooking.bookingseat.availability.model.StandingInfo;
import com.trainbooking.bookingseat.availability.model.TripHeader;
import com.trainbooking.bookingseat.common.CoachClass;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the copy of the trip and its inventory. All reads of one availability request run in one read-only transaction. */
// @Repository: data-access bean. JdbcClient (Spring JDBC) runs the explicit SQL below; there is no JPA, so every query is visible here.
// Named parameters (:tripId) are bound by Spring, which keeps the SQL safe from injection.
@Repository
public class AvailabilityRepository {

    private final JdbcClient jdbc;

    public AvailabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // One row for the trip plus a sub-select that counts its stops (stop indexes run from 0 to stop_count - 1).
    public Optional<TripHeader> findTrip(long tripId) {
        return jdbc.sql("SELECT t.id, t.status, (SELECT COUNT(*) FROM trip_stop s WHERE s.trip_id = t.id) AS stop_count "
                        + "FROM trip t WHERE t.id = :id")
                .param("id", tripId)
                .query((rs, row) -> new TripHeader(rs.getLong("id"), rs.getString("status"), rs.getInt("stop_count")))
                .optional();
    }

    /** All seats of the trip in seat-map order: by coach position, then row and column. */
    // Joins seat to coach only to sort in seat-map order (coach position, then row, then column); the join stays inside Booking's own
    // tables.
    // "class" is the seat's coach class, copied into trip_seat by the snapshot.
    public List<SeatInfo> findSeats(long tripId) {
        return jdbc.sql("SELECT s.seat_id, s.coach_id, s.class FROM trip_seat s "
                        + "JOIN trip_coach c ON c.trip_id = s.trip_id AND c.coach_id = s.coach_id "
                        + "WHERE s.trip_id = :tripId ORDER BY c.position, s.seat_row, s.seat_column, s.seat_id")
                .param("tripId", tripId)
                .query((rs, row) -> new SeatInfo(rs.getLong("seat_id"), rs.getLong("coach_id"), CoachClass.valueOf(rs.getString("class"))))
                .list();
    }

    /**
     * The reservations on the segments of the journey (a narrower query than the whole trip, because a train has
     * thousands of seat-segments). A row with an expires_at is a hold; one without is a confirmed booking.
     * There is deliberately no comparison of expires_at with the current time: a hold that has run out but that the
     * sweeper has not released yet keeps blocking the seat and is shown as held, never as free (SRS 4.2).
     */
    // Reads only the segments of the journey from the seat_reservation table. expires_at is NULL for a confirmed booking and set for a
    // hold;
    // the code only asks "is it null", it never compares the date with the clock (an expired hold still blocks the seat).
    public List<ReservationInfo> findReservations(long tripId, Journey journey) {
        return jdbc.sql("SELECT seat_id, segment_index, expires_at FROM seat_reservation "
                        + "WHERE trip_id = :tripId AND segment_index >= :fromSegment AND segment_index < :toStop")
                .param("tripId", tripId)
                .param("fromSegment", journey.fromStop())
                .param("toStop", journey.toStop())
                .query((rs, row) -> new ReservationInfo(rs.getLong("seat_id"), rs.getInt("segment_index"), rs.getObject("expires_at") != null))
                .list();
    }

    /** The change counter of the trip (0 until something changed). */
    // trip_version has a row only after the first event of the trip, so a missing row means version 0.
    public long findVersion(long tripId) {
        return jdbc.sql("SELECT version FROM trip_version WHERE trip_id = :t").param("t", tripId).query(Long.class).optional().orElse(0L);
    }

    /** The fares of the trip: one per segment and class. */
    // One fare per (segment, class) of the trip, in EGP.
    public List<FareInfo> findFares(long tripId) {
        return jdbc.sql("SELECT segment_index, class, amount FROM trip_fare WHERE trip_id = :tripId ORDER BY segment_index, class")
                .param("tripId", tripId)
                .query((rs, row) -> new FareInfo(rs.getInt("segment_index"), CoachClass.valueOf(rs.getString("class")), rs.getBigDecimal("amount")))
                .list();
    }

    /** The standing counters of every coach on every segment: a few hundred rows at most, so the whole trip is read. */
    // The counters are joined to trip_coach for the coach class. Only coaches with standing places have rows.
    public List<StandingInfo> findStanding(long tripId) {
        return jdbc.sql("SELECT u.coach_id, c.class, u.segment_index, u.capacity, u.used FROM standing_usage u "
                        + "JOIN trip_coach c ON c.trip_id = u.trip_id AND c.coach_id = u.coach_id "
                        + "WHERE u.trip_id = :tripId ORDER BY u.coach_id, u.segment_index")
                .param("tripId", tripId)
                .query((rs, row) -> new StandingInfo(rs.getLong("coach_id"), CoachClass.valueOf(rs.getString("class")),
                        rs.getInt("segment_index"), rs.getInt("capacity"), rs.getInt("used")))
                .list();
    }
}
