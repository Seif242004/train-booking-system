package com.trainbooking.notification.notification.repository;

import com.trainbooking.notification.notification.model.Email;
import com.trainbooking.notification.notification.model.EmailStatus;
import com.trainbooking.notification.notification.model.NotificationRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL of the email table (V1 migration). Plain JdbcClient with named parameters, so every statement is parameterised (no string
 * concatenation of user data). Each method joins the transaction of its caller, if there is one; the dispatcher calls these without one,
 * so each statement commits by itself.
 */
@Repository
public class EmailRepository {

    // The columns to read, listed once so every SELECT maps the same way.
    private static final String COLUMNS = "id, dedupe_key, kind, booking_ref, recipient, recipient_name, subject, body_text, body_html, "
            + "attachment_name, attachment_type, attachment, status, attempts, next_attempt_at, last_error, sent_at";

    private final JdbcClient jdbc;

    public EmailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // SELECT by the unique dedupe_key: used for the idempotency check in NotificationService.accept.
    public Optional<Email> findByDedupeKey(String dedupeKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM email WHERE dedupe_key = :k").param("k", dedupeKey).query(EmailRepository::map).optional();
    }

    // Loads one whole email, including the bodies and the PDF, just before it is sent.
    public Optional<Email> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM email WHERE id = :id").param("id", id).query(EmailRepository::map).optional();
    }

    // INSERT: new rows start PENDING with attempts 0 and next_attempt_at = now, so the poller picks them up at once. The unique constraint
    // uk_email_dedupe turns a second row for the same key into ORA-00001, which Spring reports as DuplicateKeyException.
    /** The unique key on the dedupe key makes a second email for the same key impossible (ORA-00001). */
    public Email insert(String dedupeKey, NotificationRequest.Kind kind, String reference, String recipient, String recipientName,
                        String subject, String text, String html, String attachmentName, String attachmentType, byte[] attachment, OffsetDateTime now) {
        jdbc.sql("INSERT INTO email (dedupe_key, kind, booking_ref, recipient, recipient_name, subject, body_text, body_html, "
                        + "attachment_name, attachment_type, attachment, status, attempts, next_attempt_at, created_at) "
                        + "VALUES (:key, :kind, :ref, :to, :name, :subject, :text, :html, :an, :at, :a, 'PENDING', 0, :now, :now)")
                .param("key", dedupeKey).param("kind", kind.name()).param("ref", reference).param("to", recipient).param("name", recipientName)
                .param("subject", subject).param("text", text).param("html", html).param("an", attachmentName).param("at", attachmentType)
                .param("a", attachment).param("now", now).update();
        return findByDedupeKey(dedupeKey).orElseThrow();
    }

    /** Ids of the emails that are due, oldest first. Only the ids: the bodies and attachments are loaded one at a time when sent. */
    // SELECT the oldest due PENDING ids; FETCH FIRST n ROWS ONLY is Oracle's row limit. The index ix_email_due (status, next_attempt_at)
    // serves it.
    public List<Long> findDueIds(OffsetDateTime now, int limit) {
        return jdbc.sql("SELECT id FROM email WHERE status = 'PENDING' AND next_attempt_at <= :now ORDER BY id FETCH FIRST :limit ROWS ONLY")
                .param("now", now).param("limit", limit).query(Long.class).list();
    }

    /** Marks it sent. False if it was not pending any more (so an email is never counted as sent twice). */
    // UPDATE guarded by "status = 'PENDING'": it affects 1 row only the first time, so an email is never counted as sent twice.
    public boolean markSent(long id, OffsetDateTime now) {
        return jdbc.sql("UPDATE email SET status = 'SENT', sent_at = :now, last_error = NULL WHERE id = :id AND status = 'PENDING'")
                .param("now", now).param("id", id).update() == 1;
    }

    // UPDATE: one more attempt used, the next try later, the error text kept for diagnosis (cut to fit last_error VARCHAR2(500)).
    public void scheduleRetry(long id, OffsetDateTime next, String error) {
        jdbc.sql("UPDATE email SET attempts = attempts + 1, next_attempt_at = :next, last_error = :e WHERE id = :id")
                .param("next", next).param("e", truncate(error)).param("id", id).update();
    }

    // UPDATE: gives up for good (status FAILED); the poller never selects FAILED rows again.
    public void markFailed(long id, String error) {
        jdbc.sql("UPDATE email SET status = 'FAILED', attempts = attempts + 1, last_error = :e WHERE id = :id").param("e", truncate(error)).param("id", id).update();
    }

    // Keeps the error within the column size (480 characters, below 500).
    private static String truncate(String text) {
        return text == null ? null : text.substring(0, Math.min(text.length(), 480));
    }

    // Row mapper: JDBC result row to Email record. Dates are read as OffsetDateTime because the columns are TIMESTAMP WITH TIME ZONE.
    private static Email map(ResultSet rs, int row) throws SQLException {
        return new Email(rs.getLong("id"), rs.getString("dedupe_key"), NotificationRequest.Kind.valueOf(rs.getString("kind")), rs.getString("booking_ref"),
                rs.getString("recipient"), rs.getString("recipient_name"), rs.getString("subject"), rs.getString("body_text"), rs.getString("body_html"),
                rs.getString("attachment_name"), rs.getString("attachment_type"), rs.getBytes("attachment"), EmailStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"), rs.getObject("next_attempt_at", OffsetDateTime.class), rs.getString("last_error"), rs.getObject("sent_at", OffsetDateTime.class));
    }
}
