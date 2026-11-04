package com.trainbooking.bookingseat.board.repository;

import com.trainbooking.bookingseat.board.model.SeatCounts;
import com.trainbooking.bookingseat.common.CoachClass;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only queries of the trips board. Aggregates only: nothing here loads a row per seat for a list of trips. */
@Repository
public class BoardRepository {

    public record TripRow(long id, String trainNumber, String trainName, String routeName, String status, String origin, String destination,
                          OffsetDateTime departure, OffsetDateTime arrival, int stops) {
    }

    public record ClassSeatRow(long tripId, CoachClass coachClass, SeatCounts counts) {
    }

    public record StandingRow(long tripId, long coachId, CoachClass coachClass, int segment, int capacity, int used) {
    }

    public record StandingBookingRow(long tripId, long coachId, String status, int fromStop, int toStop, int qty) {
    }

    public record SeatRow(long seatId, long coachId, String state, String reference, OffsetDateTime holdExpiresAt) {
    }

    private static final String TRIP_COLUMNS = "SELECT t.id, t.train_number, t.train_name, t.route_name, t.status, o.station_name AS origin, "
            + "d.station_name AS destination, o.departure_time AS departure, d.arrival_time AS arrival, "
            + "(SELECT COUNT(*) FROM trip_stop x WHERE x.trip_id = t.id) AS stops "
            + "FROM trip t JOIN trip_stop o ON o.trip_id = t.id AND o.stop_index = 0 "
            + "JOIN trip_stop d ON d.trip_id = t.id AND d.stop_index = (SELECT MAX(m.stop_index) FROM trip_stop m WHERE m.trip_id = t.id) ";

    private final JdbcClient jdbc;

    public BoardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static TripRow tripRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TripRow(rs.getLong("id"), rs.getString("train_number"), rs.getString("train_name"), rs.getString("route_name"),
                rs.getString("status"), rs.getString("origin"), rs.getString("destination"),
                rs.getObject("departure", OffsetDateTime.class), rs.getObject("arrival", OffsetDateTime.class), rs.getInt("stops"));
    }

    /** Trips that leave their first stop in [from, to), oldest departure first, at most {@code limit}. */
    public List<TripRow> findTrips(OffsetDateTime from, OffsetDateTime to, int limit) {
        return jdbc.sql(TRIP_COLUMNS + "WHERE o.departure_time >= :from AND o.departure_time < :to ORDER BY o.departure_time, t.id FETCH FIRST :limit ROWS ONLY")
                .param("from", from).param("to", to).param("limit", limit)
                .query((rs, n) -> tripRow(rs)).list();
    }

    public Optional<TripRow> findTrip(long tripId) {
        return jdbc.sql(TRIP_COLUMNS + "WHERE t.id = :id").param("id", tripId).query((rs, n) -> tripRow(rs)).optional();
    }

    /**
     * Seats per trip and class. A seat is confirmed if ANY segment of it has a reservation without expiry; held if it has none of
     * those but some segment with an expiry; otherwise free. (The full journey uses every segment, so "any segment" is the rule.)
     */
    public List<ClassSeatRow> seatCounts(List<Long> tripIds) {
        if (tripIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT s.trip_id, s.class, COUNT(*) AS capacity, "
                        + "SUM(CASE WHEN r.confirmed > 0 THEN 1 ELSE 0 END) AS confirmed, "
                        + "SUM(CASE WHEN r.confirmed = 0 AND r.held > 0 THEN 1 ELSE 0 END) AS held "
                        + "FROM trip_seat s LEFT JOIN (SELECT trip_id, seat_id, "
                        + "  SUM(CASE WHEN expires_at IS NULL THEN 1 ELSE 0 END) AS confirmed, SUM(CASE WHEN expires_at IS NULL THEN 0 ELSE 1 END) AS held "
                        + "  FROM seat_reservation WHERE trip_id IN (:ids) GROUP BY trip_id, seat_id) r ON r.trip_id = s.trip_id AND r.seat_id = s.seat_id "
                        + "WHERE s.trip_id IN (:ids) GROUP BY s.trip_id, s.class")
                .param("ids", tripIds)
                .query((rs, n) -> {
                    int capacity = rs.getInt("capacity");
                    int confirmed = rs.getInt("confirmed");
                    int held = rs.getInt("held");
                    return new ClassSeatRow(rs.getLong("trip_id"), CoachClass.valueOf(rs.getString("class")),
                            new SeatCounts(capacity, confirmed, held, capacity - confirmed - held));
                }).list();
    }

    public List<StandingRow> standingUsage(List<Long> tripIds) {
        if (tripIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT u.trip_id, u.coach_id, c.class, u.segment_index, u.capacity, u.used FROM standing_usage u "
                        + "JOIN trip_coach c ON c.trip_id = u.trip_id AND c.coach_id = u.coach_id WHERE u.trip_id IN (:ids)")
                .param("ids", tripIds)
                .query((rs, n) -> new StandingRow(rs.getLong("trip_id"), rs.getLong("coach_id"), CoachClass.valueOf(rs.getString("class")),
                        rs.getInt("segment_index"), rs.getInt("capacity"), rs.getInt("used")))
                .list();
    }

    /** Standing places of confirmed bookings and of running holds (their journey tells which segments they use). */
    public List<StandingBookingRow> standingBookings(List<Long> tripIds) {
        if (tripIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT b.trip_id, i.coach_id, b.status, b.from_stop, b.to_stop, i.qty FROM booking_item i "
                        + "JOIN booking b ON b.id = i.booking_id WHERE i.item_type = 'STANDING' AND b.status IN ('CONFIRMED', 'PENDING_PAYMENT') "
                        + "AND b.trip_id IN (:ids)")
                .param("ids", tripIds)
                .query((rs, n) -> new StandingBookingRow(rs.getLong("trip_id"), rs.getLong("coach_id"), rs.getString("status"),
                        rs.getInt("from_stop"), rs.getInt("to_stop"), rs.getInt("qty")))
                .list();
    }

    /** One row per seat of the trip: TAKEN (with the reference of the first confirmed booking), HELD or FREE. */
    public List<SeatRow> seatStates(long tripId) {
        return jdbc.sql("SELECT s.seat_id, s.coach_id, CASE WHEN r.confirmed = 0 AND r.held > 0 THEN r.expires END AS hold_expires, "
                        + "CASE WHEN r.confirmed > 0 THEN 'TAKEN' WHEN r.held > 0 THEN 'HELD' ELSE 'FREE' END AS state, "
                        + "CASE WHEN r.confirmed > 0 THEN (SELECT MIN(b.reference) KEEP (DENSE_RANK FIRST ORDER BY x.segment_index) "
                        + "   FROM seat_reservation x JOIN booking b ON b.id = x.booking_id "
                        + "   WHERE x.trip_id = s.trip_id AND x.seat_id = s.seat_id AND x.expires_at IS NULL) END AS reference "
                        + "FROM trip_seat s JOIN trip_coach c ON c.trip_id = s.trip_id AND c.coach_id = s.coach_id "
                        + "LEFT JOIN (SELECT seat_id, SUM(CASE WHEN expires_at IS NULL THEN 1 ELSE 0 END) AS confirmed, "
                        + "  SUM(CASE WHEN expires_at IS NULL THEN 0 ELSE 1 END) AS held, MIN(expires_at) AS expires FROM seat_reservation WHERE trip_id = :t GROUP BY seat_id) r "
                        + "  ON r.seat_id = s.seat_id "
                        + "WHERE s.trip_id = :t ORDER BY c.position, s.seat_row, s.seat_column, s.seat_id")
                .param("t", tripId)
                .query((rs, n) -> new SeatRow(rs.getLong("seat_id"), rs.getLong("coach_id"), rs.getString("state"), rs.getString("reference"),
                        rs.getObject("hold_expires", OffsetDateTime.class)))
                .list();
    }
}
