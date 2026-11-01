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
@Repository
public class AvailabilityRepository {

    private final JdbcClient jdbc;

    public AvailabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TripHeader> findTrip(long tripId) {
        return jdbc.sql("SELECT t.id, t.status, (SELECT COUNT(*) FROM trip_stop s WHERE s.trip_id = t.id) AS stop_count "
                        + "FROM trip t WHERE t.id = :id")
                .param("id", tripId)
                .query((rs, row) -> new TripHeader(rs.getLong("id"), rs.getString("status"), rs.getInt("stop_count")))
                .optional();
    }

    /** All seats of the trip in seat-map order: by coach position, then row and column. */
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
    public List<ReservationInfo> findReservations(long tripId, Journey journey) {
        return jdbc.sql("SELECT seat_id, segment_index, expires_at FROM seat_reservation "
                        + "WHERE trip_id = :tripId AND segment_index >= :fromSegment AND segment_index < :toStop")
                .param("tripId", tripId)
                .param("fromSegment", journey.fromStop())
                .param("toStop", journey.toStop())
                .query((rs, row) -> new ReservationInfo(rs.getLong("seat_id"), rs.getInt("segment_index"), rs.getObject("expires_at") != null))
                .list();
    }

    /** The fares of the trip: one per segment and class. */
    public List<FareInfo> findFares(long tripId) {
        return jdbc.sql("SELECT segment_index, class, amount FROM trip_fare WHERE trip_id = :tripId ORDER BY segment_index, class")
                .param("tripId", tripId)
                .query((rs, row) -> new FareInfo(rs.getInt("segment_index"), CoachClass.valueOf(rs.getString("class")), rs.getBigDecimal("amount")))
                .list();
    }

    /** The standing counters of every coach on every segment: a few hundred rows at most, so the whole trip is read. */
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
