package com.trainbooking.payment.refund.repository;

import com.trainbooking.payment.refund.model.Refund;
import com.trainbooking.payment.refund.model.RefundStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRepository {

    private static final String COLUMNS = "id, booking_id, payment_id, stripe_refund_id, amount, status, reason, failure_reason, attempts, next_attempt_at";

    private final JdbcClient jdbc;

    public RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Refund> findByBooking(long bookingId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund WHERE booking_id = :b").param("b", bookingId).query(RefundRepository::map).optional();
    }

    public Optional<Refund> findByIdForUpdate(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund WHERE id = :id FOR UPDATE").param("id", id).query(RefundRepository::map).optional();
    }

    public Optional<Refund> findByStripeIdForUpdate(String stripeRefundId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund WHERE stripe_refund_id = :s FOR UPDATE").param("s", stripeRefundId).query(RefundRepository::map).optional();
    }

    /** The unique key on the booking makes a second refund for the same booking impossible (ORA-00001). */
    public Refund insert(long bookingId, long paymentId, BigDecimal amount, String reason, OffsetDateTime now) {
        jdbc.sql("INSERT INTO refund (booking_id, payment_id, amount, status, reason, attempts, next_attempt_at, created_at, updated_at) "
                        + "VALUES (:b, :p, :amount, 'PENDING', :reason, 0, :now, :now, :now)")
                .param("b", bookingId).param("p", paymentId).param("amount", amount).param("reason", reason).param("now", now).update();
        return findByBooking(bookingId).orElseThrow();
    }

    /** Refunds that Stripe has not been asked for yet (or could not be reached for) and are due. */
    public List<Refund> findDueWithoutStripeRefund(OffsetDateTime now, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refund WHERE status = 'PENDING' AND stripe_refund_id IS NULL AND next_attempt_at <= :now "
                        + "ORDER BY id FETCH FIRST :limit ROWS ONLY")
                .param("now", now).param("limit", limit).query(RefundRepository::map).list();
    }

    public void setStripeRefund(long id, String stripeRefundId, RefundStatus status, String failureReason, OffsetDateTime now) {
        jdbc.sql("UPDATE refund SET stripe_refund_id = :s, status = :status, failure_reason = :f, updated_at = :now WHERE id = :id")
                .param("s", stripeRefundId).param("status", status.name()).param("f", truncate(failureReason)).param("now", now).param("id", id).update();
    }

    public void setStatus(long id, RefundStatus status, String failureReason, OffsetDateTime now) {
        jdbc.sql("UPDATE refund SET status = :status, failure_reason = :f, updated_at = :now WHERE id = :id")
                .param("status", status.name()).param("f", truncate(failureReason)).param("now", now).param("id", id).update();
    }

    public void scheduleRetry(long id, OffsetDateTime next, String error, OffsetDateTime now) {
        jdbc.sql("UPDATE refund SET attempts = attempts + 1, next_attempt_at = :next, failure_reason = :f, updated_at = :now WHERE id = :id")
                .param("next", next).param("f", truncate(error)).param("now", now).param("id", id).update();
    }

    private static String truncate(String text) {
        return text == null ? null : text.substring(0, Math.min(text.length(), 480));
    }

    private static Refund map(ResultSet rs, int row) throws SQLException {
        return new Refund(rs.getLong("id"), rs.getLong("booking_id"), rs.getLong("payment_id"), rs.getString("stripe_refund_id"),
                rs.getBigDecimal("amount"), RefundStatus.valueOf(rs.getString("status")), rs.getString("reason"), rs.getString("failure_reason"),
                rs.getInt("attempts"), rs.getObject("next_attempt_at", OffsetDateTime.class));
    }
}
