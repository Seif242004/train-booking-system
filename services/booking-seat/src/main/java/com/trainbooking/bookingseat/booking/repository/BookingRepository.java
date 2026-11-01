package com.trainbooking.bookingseat.booking.repository;

import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingItemView;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.service.BookingPricing;
import com.trainbooking.bookingseat.common.CoachClass;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Data access for bookings and their items. Parameterised SQL only; the caller owns the transaction. */
@Repository
public class BookingRepository {

    private static final String COLUMNS = "id, reference, user_id, user_email, user_name, trip_id, from_stop, to_stop, status, refund_status, amount, "
            + "currency, expires_at, created_at, confirmed_at, cancelled_at, cancelled_by, cancelled_by_role, cancel_reason, next_attempt_at, attempts";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public BookingRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    /** Inserts a PENDING_PAYMENT booking and returns its id. A clash of the reference (unique) is a DuplicateKeyException. */
    public long insert(String reference, String userId, String userEmail, String userName, long tripId, int fromStop, int toStop,
                       BigDecimal amount, OffsetDateTime expiresAt, OffsetDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO booking (reference, user_id, user_email, user_name, trip_id, from_stop, to_stop, status, amount, expires_at, created_at) "
                        + "VALUES (:ref, :user, :email, :name, :trip, :from, :to, 'PENDING_PAYMENT', :amount, :exp, :created)")
                .param("ref", reference).param("user", userId).param("email", userEmail == null ? "" : userEmail).param("name", userName == null ? "" : userName)
                .param("trip", tripId).param("from", fromStop).param("to", toStop).param("amount", amount).param("exp", expiresAt).param("created", createdAt)
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public void insertItems(long bookingId, List<BookingPricing.Line> lines, Map<Long, InventoryRepository.SeatRef> seats, Map<Long, InventoryRepository.CoachRef> coaches) {
        List<SqlParameterSource> rows = new ArrayList<>();
        for (BookingPricing.Line line : lines) {
            InventoryRepository.SeatRef seat = line.seatId() == null ? null : seats.get(line.seatId());
            rows.add(new MapSqlParameterSource().addValue("b", bookingId).addValue("type", line.type())
                    .addValue("seat", line.seatId(), java.sql.Types.NUMERIC)
                    .addValue("coach", "STANDING".equals(line.type()) ? line.coachId() : null, java.sql.Types.NUMERIC)
                    .addValue("qty", line.qty()).addValue("price", line.price())
                    // What the passenger was shown, kept with the line: the booking stays readable whatever a later snapshot changes.
                    .addValue("seatNumber", seat == null ? null : seat.seatNumber())
                    .addValue("coachCode", seat != null ? seat.coachCode() : coaches.get(line.coachId()).code())
                    .addValue("coachClass", (seat != null ? seat.coachClass() : coaches.get(line.coachId()).coachClass()).name()));
        }
        batch.batchUpdate("INSERT INTO booking_item (booking_id, item_type, seat_id, coach_id, qty, price, seat_number, coach_code, coach_class) "
                        + "VALUES (:b, :type, :seat, :coach, :qty, :price, :seatNumber, :coachCode, :coachClass)",
                rows.toArray(SqlParameterSource[]::new));
    }

    public Optional<Booking> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking WHERE id = :id").param("id", id).query((rs, row) -> map(rs)).optional();
    }

    /** Reads the booking and locks its row until the end of the transaction: every change of a booking starts with this. */
    public Optional<Booking> findForUpdate(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking WHERE id = :id FOR UPDATE").param("id", id).query((rs, row) -> map(rs)).optional();
    }

    public Optional<Booking> findByReference(String reference) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking WHERE reference = :ref").param("ref", reference).query((rs, row) -> map(rs)).optional();
    }

    /** The holds that are past their expiry and whose next attempt is due, oldest first. */
    public List<Long> findDueHoldIds(OffsetDateTime now, int limit) {
        return jdbc.sql("SELECT id FROM booking WHERE status = 'PENDING_PAYMENT' AND expires_at <= :now AND (next_attempt_at IS NULL OR next_attempt_at <= :now) "
                        + "ORDER BY expires_at, id FETCH FIRST :limit ROWS ONLY")
                .param("now", now).param("limit", limit).query(Long.class).list();
    }

    /** The user ids and trip of the active holds of a trip (for cancelling the trip). */
    public List<Long> findPendingIdsOfTrip(long tripId) {
        return jdbc.sql("SELECT id FROM booking WHERE trip_id = :t AND status = 'PENDING_PAYMENT' ORDER BY id").param("t", tripId).query(Long.class).list();
    }

    public void markStatus(long id, BookingStatus status) {
        jdbc.sql("UPDATE booking SET status = :status, next_attempt_at = NULL WHERE id = :id").param("status", status.name()).param("id", id).update();
    }

    public void markConfirmed(long id, OffsetDateTime now) {
        jdbc.sql("UPDATE booking SET status = 'CONFIRMED', confirmed_at = :now, next_attempt_at = NULL WHERE id = :id").param("now", now).param("id", id).update();
    }

    /** The sweeper will look at this hold again at {@code next}; {@code attempts} counts the tries, for the backoff. */
    public void scheduleRetry(long id, OffsetDateTime next, int attempts) {
        jdbc.sql("UPDATE booking SET next_attempt_at = :next, attempts = :attempts WHERE id = :id").param("next", next).param("attempts", attempts).param("id", id).update();
    }

    public List<BookingItemView> findItems(long bookingId) {
        // Everything comes from the line itself, not from the copy of the trip: a later snapshot cannot change or lose it.
        return jdbc.sql("SELECT item_type, seat_id, seat_number, coach_id, coach_code, coach_class, qty, price FROM booking_item WHERE booking_id = :b ORDER BY id")
                .param("b", bookingId)
                .query((rs, row) -> new BookingItemView(rs.getString("item_type"), rs.getObject("seat_id") == null ? null : rs.getLong("seat_id"), rs.getString("seat_number"),
                        rs.getObject("coach_id") == null ? 0 : rs.getLong("coach_id"), rs.getString("coach_code"), CoachClass.valueOf(rs.getString("coach_class")), rs.getInt("qty"), money(rs, "price")))
                .list();
    }

    public Optional<BookingView.TripInfo> findTripInfo(long tripId, int fromStop, int toStop) {
        return jdbc.sql("SELECT t.train_number, t.train_name, t.route_name, t.departure_date FROM trip t WHERE t.id = :t").param("t", tripId)
                .query((rs, row) -> new BookingView.TripInfo(rs.getString("train_number"), rs.getString("train_name"), rs.getString("route_name"),
                        rs.getObject("departure_date", LocalDate.class), stop(tripId, fromStop), stop(tripId, toStop)))
                .optional();
    }

    private BookingView.StopInfo stop(long tripId, int index) {
        // A trip that was never paid may get other stops in a later snapshot: a stop that is gone is shown as unknown, not as a failure.
        return jdbc.sql("SELECT stop_index, station_code, station_name, arrival_time, departure_time FROM trip_stop WHERE trip_id = :t AND stop_index = :i")
                .param("t", tripId).param("i", index)
                .query((rs, row) -> new BookingView.StopInfo(rs.getInt("stop_index"), rs.getString("station_code"), rs.getString("station_name"),
                        rs.getObject("arrival_time", OffsetDateTime.class), rs.getObject("departure_time", OffsetDateTime.class)))
                .optional().orElse(new BookingView.StopInfo(index, "?", "?", null, null));
    }

    /** Money is always shown with two decimals (Oracle returns 30 for 30.00). */
    private static BigDecimal money(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column).setScale(2);
    }

    private static Booking map(ResultSet rs) throws SQLException {
        return new Booking(rs.getLong("id"), rs.getString("reference"), rs.getString("user_id"), rs.getString("user_email"), rs.getString("user_name"),
                rs.getLong("trip_id"), rs.getInt("from_stop"), rs.getInt("to_stop"), BookingStatus.valueOf(rs.getString("status")),
                RefundStatus.valueOf(rs.getString("refund_status")), money(rs, "amount"), rs.getString("currency"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("confirmed_at", OffsetDateTime.class), rs.getObject("cancelled_at", OffsetDateTime.class),
                rs.getString("cancelled_by"), rs.getString("cancelled_by_role"), rs.getString("cancel_reason"),
                rs.getObject("next_attempt_at", OffsetDateTime.class), rs.getInt("attempts"));
    }
}
