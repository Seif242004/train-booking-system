package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import com.trainbooking.bookingseat.booking.service.ConfirmationService;
import com.trainbooking.bookingseat.outbox.model.OutboxMessage;
import com.trainbooking.bookingseat.outbox.repository.OutboxRepository;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** The cancellation is all or nothing: if the last step fails, the seats, the status and the queue are exactly as they were. */
class CancellationAtomicityDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9602;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice");

    @Autowired
    Holds holds;
    @Autowired
    ConfirmationService confirmations;
    @Autowired
    CancellationService cancellations;
    @MockitoSpyBean
    OutboxRepository outbox;

    @AfterEach
    void realOutbox() {
        reset(outbox);
    }

    @Test
    void ifTheFollowUpCannotBeQueued_nothingIsCancelled() {
        insertTrip(TRIP);
        BookingView booking = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of(new HoldRequest.StandingRequest(30L, 2))));
        confirmations.confirm(booking.id());
        committed.clear();
        int queuedBefore = rows("outbox");
        doThrow(new IllegalStateException("the database failed")).when(outbox).insert(eq(OutboxMessage.Kind.CANCELLATION_EMAIL), anyLong(), any(), any());

        assertThatThrownBy(() -> cancellations.cancel(CancellationService.Actor.user("alice-sub"), booking.id(), null)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT status FROM booking WHERE id = :id").param("id", booking.id()).query(String.class).single()).isEqualTo("CONFIRMED");
        assertThat(rows("seat_reservation WHERE booking_id = " + booking.id())).isEqualTo(3);                       // the seat is still taken (3 segments)
        assertThat(jdbc.sql("SELECT NVL(SUM(used), 0) FROM standing_usage").query(Integer.class).single()).isEqualTo(6); // and the standing places
        assertThat(rows("outbox")).isEqualTo(queuedBefore);                                                          // no ticket invalidation, no refund request
        assertThat(committed.seatEvents()).isEmpty();                                                                // and nobody was told the seat is free
    }
}
