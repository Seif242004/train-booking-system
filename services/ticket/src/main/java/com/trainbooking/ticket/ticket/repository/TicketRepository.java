package com.trainbooking.ticket.ticket.repository;

import com.trainbooking.ticket.ticket.model.Ticket;
import com.trainbooking.ticket.ticket.model.TicketStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TicketRepository {

    private static final String COLUMNS = "id, booking_id, reference, qr_token, status, created_at, invalidated_at";

    private final JdbcClient jdbc;

    public TicketRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Ticket> findByBooking(long bookingId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ticket WHERE booking_id = :b").param("b", bookingId).query(TicketRepository::map).optional();
    }

    public Optional<Ticket> findByBookingForUpdate(long bookingId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ticket WHERE booking_id = :b FOR UPDATE").param("b", bookingId).query(TicketRepository::map).optional();
    }

    public Optional<byte[]> findPdf(long bookingId) {
        return jdbc.sql("SELECT pdf FROM ticket WHERE booking_id = :b").param("b", bookingId).query((rs, row) -> rs.getBytes("pdf")).optional();
    }

    /** The unique key on the booking makes two tickets for one booking impossible (ORA-00001). */
    public Ticket insert(long bookingId, String reference, String qrToken, byte[] pdf, OffsetDateTime now) {
        jdbc.sql("INSERT INTO ticket (booking_id, reference, qr_token, status, pdf, created_at) VALUES (:b, :ref, :token, 'VALID', :pdf, :now)")
                .param("b", bookingId).param("ref", reference).param("token", qrToken).param("pdf", pdf).param("now", now).update();
        return findByBooking(bookingId).orElseThrow();
    }

    public void invalidate(long id, OffsetDateTime now) {
        jdbc.sql("UPDATE ticket SET status = 'INVALIDATED', invalidated_at = :now WHERE id = :id AND status = 'VALID'").param("now", now).param("id", id).update();
    }

    private static Ticket map(ResultSet rs, int row) throws SQLException {
        return new Ticket(rs.getLong("id"), rs.getLong("booking_id"), rs.getString("reference"), rs.getString("qr_token"),
                TicketStatus.valueOf(rs.getString("status")), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("invalidated_at", OffsetDateTime.class));
    }
}
