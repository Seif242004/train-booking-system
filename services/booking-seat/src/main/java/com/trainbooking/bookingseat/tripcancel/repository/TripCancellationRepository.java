package com.trainbooking.bookingseat.tripcancel.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for the cancellation of a whole trip (the trip rows are Booking's own copy; the booking rows are read for the counts). */
@Repository
public class TripCancellationRepository {

    /** The cancellation columns of the copy of a trip. */
    public record State(String status, String reason, String cancelledBy, boolean trainScheduleNotified) {
    }

    private final JdbcClient jdbc;

    public TripCancellationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<State> find(long tripId) {
        return jdbc.sql("SELECT status, cancel_reason, cancelled_by, tns_marked FROM trip WHERE id = :id").param("id", tripId)
                .query((rs, row) -> new State(rs.getString("status"), rs.getString("cancel_reason"), rs.getString("cancelled_by"), rs.getInt("tns_marked") == 1)).optional();
    }

    /** OPEN -> CANCELLING, with who and why. The trip row must be locked by the caller. */
    public void markCancelling(long tripId, String reason, String adminId, OffsetDateTime now) {
        jdbc.sql("UPDATE trip SET status = 'CANCELLING', cancel_reason = :reason, cancelled_by = :by, cancel_requested_at = :now WHERE id = :id AND status = 'OPEN'")
                .param("reason", reason).param("by", adminId).param("now", now).param("id", tripId).update();
    }

    public void setTrainScheduleNotified(long tripId) {
        jdbc.sql("UPDATE trip SET tns_marked = 1 WHERE id = :id").param("id", tripId).update();
    }

    /** CANCELLING -> CANCELLED. @return true if the trip was CANCELLING */
    public boolean markCancelled(long tripId) {
        return jdbc.sql("UPDATE trip SET status = 'CANCELLED' WHERE id = :id AND status = 'CANCELLING'").param("id", tripId).update() == 1;
    }

    public List<Long> findCancellingTripIds() {
        return jdbc.sql("SELECT id FROM trip WHERE status = 'CANCELLING' ORDER BY id").query(Long.class).list();
    }

    public List<Long> findConfirmedBookingIds(long tripId) {
        return jdbc.sql("SELECT id FROM booking WHERE trip_id = :t AND status = 'CONFIRMED' ORDER BY id").param("t", tripId).query(Long.class).list();
    }

    public int count(long tripId, String bookingStatus) {
        return jdbc.sql("SELECT COUNT(*) FROM booking WHERE trip_id = :t AND status = :s").param("t", tripId).param("s", bookingStatus).query(Integer.class).single();
    }

    public int countRefundsFailed(long tripId) {
        return jdbc.sql("SELECT COUNT(*) FROM booking WHERE trip_id = :t AND refund_status = 'REFUND_FAILED'").param("t", tripId).query(Integer.class).single();
    }
}
