package com.trainbooking.payment.outbox.repository;

import com.trainbooking.payment.outbox.model.OutboxMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OutboxMessage.Kind kind, long bookingId, String payload, OffsetDateTime now) {
        jdbc.sql("INSERT INTO outbox (kind, booking_id, payload, status, attempts, next_attempt_at, created_at) VALUES (:k, :b, :p, 'PENDING', 0, :now, :now)")
                .param("k", kind.name()).param("b", bookingId).param("p", payload).param("now", now).update();
    }

    /** The oldest messages that are due. Within one booking they go out in the order they were written (the id order). */
    public List<OutboxMessage> findDue(OffsetDateTime now, int limit) {
        // NOT EXISTS: a message waits for the earlier pending message of the same booking, so the news of a booking is never delivered
        // out of order (REFUNDED must not arrive before REFUND_PENDING).
        return jdbc.sql("SELECT o.id, o.kind, o.booking_id, o.payload, o.attempts, o.next_attempt_at FROM outbox o "
                        + "WHERE o.status = 'PENDING' AND o.next_attempt_at <= :now "
                        + "AND NOT EXISTS (SELECT 1 FROM outbox e WHERE e.booking_id = o.booking_id AND e.status = 'PENDING' AND e.id < o.id) "
                        + "ORDER BY o.id FETCH FIRST :limit ROWS ONLY")
                .param("now", now).param("limit", limit).query(OutboxRepository::map).list();
    }

    /** True if the message was still pending, so a message is never marked twice. */
    public boolean markSent(long id) {
        return jdbc.sql("UPDATE outbox SET status = 'SENT', last_error = NULL WHERE id = :id AND status = 'PENDING'").param("id", id).update() == 1;
    }

    /** Booking refused the message for good (for example it does not know the booking): retrying cannot help. */
    public void markFailed(long id, String error) {
        jdbc.sql("UPDATE outbox SET status = 'FAILED', last_error = :e WHERE id = :id").param("e", truncate(error)).param("id", id).update();
    }

    public void scheduleRetry(long id, OffsetDateTime next, String error) {
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1, next_attempt_at = :next, last_error = :e WHERE id = :id")
                .param("next", next).param("e", truncate(error)).param("id", id).update();
    }

    private static String truncate(String text) {
        return text == null ? null : text.substring(0, Math.min(text.length(), 480));
    }

    private static OutboxMessage map(ResultSet rs, int row) throws SQLException {
        return new OutboxMessage(rs.getLong("id"), OutboxMessage.Kind.valueOf(rs.getString("kind")), rs.getLong("booking_id"),
                rs.getString("payload"), rs.getInt("attempts"), rs.getObject("next_attempt_at", OffsetDateTime.class));
    }
}
