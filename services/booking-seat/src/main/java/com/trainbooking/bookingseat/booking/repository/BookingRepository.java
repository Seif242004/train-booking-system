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
            + "currency, expires_at, created_at, confirmed_at, cancelled_at, cancelled_by, cancelled_by_role, cancel_reason, next_attempt_at, attempts, "
            + "payment_started";

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

    /**
     * Adds one line: a seat, or the standing places of one coach. The line keeps what the passenger was shown (the seat number, the
     * coach and its class), so the booking stays readable whatever a later snapshot changes.
     */
    public void insertItem(long bookingId, BookingPricing.Line line, InventoryRepository.SeatRef seat, InventoryRepository.CoachRef coach) {
        if (seat != null) {
            jdbc.sql("INSERT INTO booking_item (booking_id, item_type, seat_id, qty, price, seat_number, coach_code, coach_class) "
                            + "VALUES (:b, 'SEAT', :seat, 1, :price, :number, :coachCode, :coachClass)")
                    .param("b", bookingId).param("seat", seat.seatId()).param("price", line.price()).param("number", seat.seatNumber())
                    .param("coachCode", seat.coachCode()).param("coachClass", seat.coachClass().name()).update();
        } else {
            jdbc.sql("INSERT INTO booking_item (booking_id, item_type, coach_id, qty, price, coach_code, coach_class) "
                            + "VALUES (:b, 'STANDING', :coach, :qty, :price, :coachCode, :coachClass)")
                    .param("b", bookingId).param("coach", coach.coachId()).param("qty", line.qty()).param("price", line.price())
                    .param("coachCode", coach.code()).param("coachClass", coach.coachClass().name()).update();
        }
    }

    /** The standing places of a coach in a booking: how many, or empty if none. */
    public Optional<Integer> findStandingQty(long bookingId, long coachId) {
        return jdbc.sql("SELECT qty FROM booking_item WHERE booking_id = :b AND item_type = 'STANDING' AND coach_id = :c").param("b", bookingId).param("c", coachId)
                .query(Integer.class).optional();
    }

    public void updateStandingItem(long bookingId, long coachId, int qty, BigDecimal price) {
        jdbc.sql("UPDATE booking_item SET qty = :qty, price = :price WHERE booking_id = :b AND item_type = 'STANDING' AND coach_id = :c")
                .param("qty", qty).param("price", price).param("b", bookingId).param("c", coachId).update();
    }

    public boolean seatItemExists(long bookingId, long seatId) {
        return jdbc.sql("SELECT COUNT(*) FROM booking_item WHERE booking_id = :b AND item_type = 'SEAT' AND seat_id = :s").param("b", bookingId).param("s", seatId)
                .query(Integer.class).single() > 0;
    }

    public int deleteSeatItem(long bookingId, long seatId) {
        return jdbc.sql("DELETE FROM booking_item WHERE booking_id = :b AND item_type = 'SEAT' AND seat_id = :s").param("b", bookingId).param("s", seatId).update();
    }

    public int deleteStandingItem(long bookingId, long coachId) {
        return jdbc.sql("DELETE FROM booking_item WHERE booking_id = :b AND item_type = 'STANDING' AND coach_id = :c").param("b", bookingId).param("c", coachId).update();
    }

    public int countSeatItems(long bookingId) {
        return jdbc.sql("SELECT COUNT(*) FROM booking_item WHERE booking_id = :b AND item_type = 'SEAT'").param("b", bookingId).query(Integer.class).single();
    }

    public int countItems(long bookingId) {
        return jdbc.sql("SELECT COUNT(*) FROM booking_item WHERE booking_id = :b").param("b", bookingId).query(Integer.class).single();
    }

    /** Sets the amount of the booking to the sum of its lines: the amount is always worked out on the server from the lines. */
    public BigDecimal recomputeAmount(long bookingId) {
        BigDecimal total = jdbc.sql("SELECT NVL(SUM(price), 0) FROM booking_item WHERE booking_id = :b").param("b", bookingId).query(BigDecimal.class).single().setScale(2);
        jdbc.sql("UPDATE booking SET amount = :amount WHERE id = :b").param("amount", total).param("b", bookingId).update();
        return total;
    }

    public void setPaymentStarted(long bookingId, boolean started) {
        jdbc.sql("UPDATE booking SET payment_started = :v WHERE id = :b").param("v", started ? 1 : 0).param("b", bookingId).update();
    }

    /** The cancellation of a confirmed booking: who did it, when, and (for an admin) why. The refund starts as REFUND_PENDING. */
    public void markCancelled(long id, OffsetDateTime now, String cancelledBy, String role, String reason, RefundStatus refundStatus) {
        jdbc.sql("UPDATE booking SET status = 'CANCELLED', cancelled_at = :now, cancelled_by = :by, cancelled_by_role = :role, cancel_reason = :reason, "
                        + "refund_status = :refund, next_attempt_at = NULL WHERE id = :id")
                .param("now", now).param("by", cancelledBy).param("role", role).param("reason", reason).param("refund", refundStatus.name()).param("id", id).update();
    }

    /** The bookings of a user, newest first: holds, confirmed and cancelled ones, and any that has a refund (an expired hold that was paid). */
    public List<Booking> findOfUser(String userId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking WHERE user_id = :u AND (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED') OR refund_status <> 'NONE') "
                        + "ORDER BY created_at DESC, id DESC FETCH FIRST :limit ROWS ONLY")
                .param("u", userId).param("limit", limit).query((rs, row) -> map(rs)).list();
    }

    /**
     * Support search: every filter is optional. The email is a case-insensitive "contains" (LIKE characters in it are escaped), the reference an
     * exact match ignoring case. The values are bound parameters; the SQL text only ever contains the fixed conditions of the filters that are set.
     */
    public List<Booking> search(String reference, String email, Long tripId, BookingStatus status, RefundStatus refundStatus, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM booking WHERE 1 = 1");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        if (reference != null) {
            sql.append(" AND UPPER(reference) = :ref");
            params.put("ref", reference.toUpperCase());
        }
        if (email != null) {
            sql.append(" AND LOWER(user_email) LIKE :email ESCAPE '\\'");
            params.put("email", "%" + email.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (tripId != null) {
            sql.append(" AND trip_id = :trip");
            params.put("trip", tripId);
        }
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
        if (refundStatus != null) {
            sql.append(" AND refund_status = :refund");
            params.put("refund", refundStatus.name());
        }
        sql.append(" ORDER BY created_at DESC, id DESC FETCH FIRST :limit ROWS ONLY");
        params.put("limit", limit);
        return batch.query(sql.toString(), params, (rs, row) -> map(rs));
    }

    public void setRefundStatus(long bookingId, RefundStatus status) {
        jdbc.sql("UPDATE booking SET refund_status = :s WHERE id = :b").param("s", status.name()).param("b", bookingId).update();
    }

    /** The active hold (PENDING_PAYMENT) of a user on a trip, if there is one. At most one exists: a unique index guarantees it. */
    public Optional<Booking> findActiveHold(String userId, long tripId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking WHERE user_id = :u AND trip_id = :t AND status = 'PENDING_PAYMENT'").param("u", userId).param("t", tripId)
                .query((rs, row) -> map(rs)).optional();
    }

    public Optional<Booking> findActiveHoldForUpdate(String userId, long tripId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM booking WHERE user_id = :u AND trip_id = :t AND status = 'PENDING_PAYMENT' FOR UPDATE").param("u", userId).param("t", tripId)
                .query((rs, row) -> map(rs)).optional();
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
                rs.getObject("next_attempt_at", OffsetDateTime.class), rs.getInt("attempts"), rs.getInt("payment_started") == 1);
    }
}
