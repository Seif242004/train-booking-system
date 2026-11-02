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

    public Optional<Payment> findByBooking(long bookingId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE booking_id = :b").param("b", bookingId).query(PaymentRepository::map).optional();
    }

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

    public void setStatus(long id, PaymentStatus status, OffsetDateTime now) {
        jdbc.sql("UPDATE payment SET status = :s, updated_at = :now WHERE id = :id").param("s", status.name()).param("now", now).param("id", id).update();
    }

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

    public void touch(long id, OffsetDateTime now) {
        jdbc.sql("UPDATE payment SET updated_at = :now WHERE id = :id").param("now", now).param("id", id).update();
    }

    public void markEnded(long id) {
        jdbc.sql("UPDATE payment SET ended = 1 WHERE id = :id").param("id", id).update();
    }

    public void setLastError(long id, String error, OffsetDateTime now) {
        jdbc.sql("UPDATE payment SET last_error = :e, updated_at = :now WHERE id = :id").param("e", error).param("now", now).param("id", id).update();
    }

    private static Payment map(ResultSet rs, int row) throws SQLException {
        return new Payment(rs.getLong("id"), rs.getLong("booking_id"), rs.getString("stripe_payment_intent_id"),
                PaymentStatus.valueOf(rs.getString("status")), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("last_error"),
                rs.getInt("revision"), rs.getInt("ended") == 1, rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
