package com.trainbooking.payment.payment.repository;

import com.trainbooking.payment.payment.model.Payment;
import com.trainbooking.payment.payment.model.PaymentStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access of the payment table. The methods that lock ({@code ...ForUpdate}) must be called inside a transaction. */
// @Repository marks the data-access class (Spring turns SQL errors into its DataAccessException family, for example
// DuplicateKeyException for ORA-00001). JdbcClient runs plain SQL with named parameters (:name), so every value is bound, never glued in.
// The unique keys of the table are what protect us: one payment per booking (uk_payment_booking) and one per Stripe intent.
@Repository
public class PaymentRepository {

    private static final String COLUMNS = "id, booking_id, stripe_payment_intent_id, status, amount, currency, last_error, revision, ended, created_at, updated_at";

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the row of the booking until the end of the transaction: the one way the steps of a payment are ordered. */
    public Optional<Payment> findByBookingForUpdate(long bookingId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE booking_id = :b FOR UPDATE").param("b", bookingId).query(PaymentRepository::map).optional();
    }

    // A plain read, no lock. Use it only where the row cannot change under you, or after the row was locked in the same transaction.
    public Optional<Payment> findByBooking(long bookingId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE booking_id = :b").param("b", bookingId).query(PaymentRepository::map).optional();
    }

    // Used by the webhook: Stripe names the intent, so the payment is found through uk_payment_intent and locked, to order the webhook
    // against a cancel or an invalidate of the same booking.
    public Optional<Payment> findByIntentForUpdate(String intentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE stripe_payment_intent_id = :i FOR UPDATE").param("i", intentId).query(PaymentRepository::map).optional();
    }

    /** A new row. The unique key on the booking makes two creations at the same moment impossible: the second one fails with ORA-00001. */
    public Payment insert(long bookingId, PaymentStatus status, BigDecimal amount, String currency, boolean ended, OffsetDateTime now) {
        jdbc.sql("INSERT INTO payment (booking_id, status, amount, currency, revision, ended, created_at, updated_at) "
                        + "VALUES (:b, :status, :amount, :currency, 1, :ended, :now, :now)")
                .param("b", bookingId).param("status", status.name()).param("amount", amount).param("currency", currency)
                .param("ended", ended ? 1 : 0).param("now", now)
                .update();
        return findByBooking(bookingId).orElseThrow();
    }

    // A status change with the time of the change. Callers lock the row first, so two requests never overwrite each other's decision.
    public void setStatus(long id, PaymentStatus status, OffsetDateTime now) {
        jdbc.sql("UPDATE payment SET status = :s, updated_at = :now WHERE id = :id").param("s", status.name()).param("now", now).param("id", id).update();
    }

    // Attaches the Stripe intent when it was created and moves CREATING to PENDING; the earlier error text is cleared.
    public void setIntent(long id, String intentId, PaymentStatus status, OffsetDateTime now) {
        jdbc.sql("UPDATE payment SET stripe_payment_intent_id = :i, status = :s, last_error = NULL, updated_at = :now WHERE id = :id")
                .param("i", intentId).param("s", status.name()).param("now", now).param("id", id).update();
    }

    /** Starts another payment for the booking after the earlier one was invalidated: next revision, no intent yet. */
    public void restart(long id, BigDecimal amount, String currency, OffsetDateTime now) {
        jdbc.sql("UPDATE payment SET stripe_payment_intent_id = NULL, status = 'CREATING', amount = :amount, currency = :currency, "
                        + "revision = revision + 1, last_error = NULL, updated_at = :now WHERE id = :id")
                .param("amount", amount).param("currency", currency).param("now", now).param("id", id).update();
    }

    /** Removes a row that never got an intent and was not cancelled meanwhile (a creation Stripe refused for good). */
    public void deleteIfCreating(long id) {
        jdbc.sql("DELETE FROM payment WHERE id = :id AND status = 'CREATING' AND stripe_payment_intent_id IS NULL").param("id", id).update();
    }

    // Refreshes updated_at only. A CREATING row older than 60 seconds belongs to a call that died; touching it claims it again.
    public void touch(long id, OffsetDateTime now) {
        jdbc.sql("UPDATE payment SET updated_at = :now WHERE id = :id").param("now", now).param("id", id).update();
    }

    // Sets ended = 1: from now on no payment may be created for this booking (the hold is over). It is never set back to 0.
    public void markEnded(long id) {
        jdbc.sql("UPDATE payment SET ended = 1 WHERE id = :id").param("id", id).update();
    }

    public void setLastError(long id, String error, OffsetDateTime now) {
        jdbc.sql("UPDATE payment SET last_error = :e, updated_at = :now WHERE id = :id").param("e", error).param("now", now).param("id", id).update();
    }

    // Converts one SQL row into a Payment record (used as a method reference by query()).
    private static Payment map(ResultSet rs, int row) throws SQLException {
        return new Payment(rs.getLong("id"), rs.getLong("booking_id"), rs.getString("stripe_payment_intent_id"),
                PaymentStatus.valueOf(rs.getString("status")), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("last_error"),
                rs.getInt("revision"), rs.getInt("ended") == 1, rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
