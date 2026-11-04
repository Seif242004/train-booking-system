package com.trainbooking.bookingseat.outbox.repository;

import com.trainbooking.bookingseat.outbox.model.OutboxMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// @Repository: data-access bean. The outbox is the pattern "write the intention to call another service in the SAME transaction as the
// change, deliver it later with retries". It replaces a message broker in this project.
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // status starts as PENDING and next_attempt_at as "now", so the dispatcher picks the job up at its next tick.
    public long insert(OutboxMessage.Kind kind, long bookingId, String payload, OffsetDateTime now) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("INSERT INTO outbox (kind, booking_id, payload, status, attempts, next_attempt_at, created_at) VALUES (:k, :b, :p, 'PENDING', 0, :now, :now)")
                .param("k", kind.name()).param("b", bookingId).param("p", payload).param("now", now).update(key, "id");
        return key.getKey().longValue();
    }

    /**
     * The oldest due jobs. A job waits for an earlier pending job of the same booking only where the ORDER matters: the ticket (made by
      * TICKET_EMAIL) must exist before TICKET_INVALIDATE turns it off, so those two form one chain. The refund request, the cancellation
      * email
     * and the refund email each stand alone: a Ticket or Notification that is down must never hold back the passenger's money.
     */
    // Takes the oldest due jobs (status PENDING and next_attempt_at reached). NOT EXISTS skips a job when an EARLIER pending job of the
    // same
    // booking is in the same "chain", so order is kept where it matters: TICKET_EMAIL and TICKET_INVALIDATE share the chain TICKET (the
    // ticket must exist before it is invalidated); every other kind is its own chain, so a broken e-mail never delays a refund.
    public List<OutboxMessage> findDue(OffsetDateTime now, int limit) {
        return jdbc.sql("SELECT o.id, o.kind, o.booking_id, o.payload, o.attempts, o.next_attempt_at FROM outbox o "
                        + "WHERE o.status = 'PENDING' AND o.next_attempt_at <= :now "
                        + "AND NOT EXISTS (SELECT 1 FROM outbox e WHERE e.booking_id = o.booking_id AND e.status = 'PENDING' AND e.id < o.id "
                        + "AND (CASE e.kind WHEN 'TICKET_EMAIL' THEN 'TICKET' WHEN 'TICKET_INVALIDATE' THEN 'TICKET' ELSE e.kind END) "
                        + "  = (CASE o.kind WHEN 'TICKET_EMAIL' THEN 'TICKET' WHEN 'TICKET_INVALIDATE' THEN 'TICKET' ELSE o.kind END)) "
                        + "ORDER BY o.id FETCH FIRST :limit ROWS ONLY")
                .param("now", now).param("limit", limit).query(OutboxRepository::map).list();
    }

    // Used to avoid queueing a second ticket e-mail while one is waiting.
    public boolean hasPending(long bookingId, OutboxMessage.Kind kind) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE booking_id = :b AND kind = :k AND status = 'PENDING'")
                .param("b", bookingId).param("k", kind.name()).query(Integer.class).single() > 0;
    }

    // "AND status = 'PENDING'" makes this a compare-and-set: it returns false if another run already marked the job.
    public boolean markSent(long id, OffsetDateTime now) {
        return jdbc.sql("UPDATE outbox SET status = 'SENT', sent_at = :now, last_error = NULL WHERE id = :id AND status = 'PENDING'")
                .param("now", now).param("id", id).update() == 1;
    }

    // Counts the attempt and sets the next time; the error text is cut to 480 characters (the column holds 500).
    public void scheduleRetry(long id, OffsetDateTime next, String error) {
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1, next_attempt_at = :next, last_error = :e WHERE id = :id")
                .param("next", next).param("e", truncate(error)).param("id", id).update();
    }

    // FAILED jobs are never picked again; an admin finds them in the table and in the error log.
    public void markFailed(long id, String error) {
        jdbc.sql("UPDATE outbox SET status = 'FAILED', attempts = attempts + 1, last_error = :e WHERE id = :id").param("e", truncate(error)).param("id", id).update();
    }

    private static String truncate(String text) {
        return text == null ? null : text.substring(0, Math.min(text.length(), 480));
    }

    private static OutboxMessage map(ResultSet rs, int row) throws SQLException {
        return new OutboxMessage(rs.getLong("id"), OutboxMessage.Kind.valueOf(rs.getString("kind")), rs.getLong("booking_id"), rs.getString("payload"),
                rs.getInt("attempts"), rs.getObject("next_attempt_at", OffsetDateTime.class));
    }
}
