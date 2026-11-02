package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.service.HoldSweeper;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** The sweeper really is a scheduled job: with it switched on, nobody calls it by hand and an expired hold is released all the same. */
@TestPropertySource(properties = {"trainbooking.sweeper.enabled=true", "trainbooking.sweeper.fixed-delay-ms=200"})
class SweeperWiringDatabaseTest extends DatabaseTest {

    @Autowired
    com.trainbooking.bookingseat.testsupport.Holds holds;

    @Autowired
    ApplicationContext context;

    @Test
    void anExpiredHoldIsReleasedByTheScheduleItself_withoutAnyoneCallingTheSweeper() throws Exception {
        insertTrip(9401);
        assertThat(context.getBeansOfType(HoldSweeper.class)).hasSize(1);
        BookingView booking = holds.hold(new AuthenticatedUser("alice-sub", "alice@example.com", "Alice"), new HoldRequest(9401L, 0, 3, List.of(1L), List.of()));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        String status = "PENDING_PAYMENT";
        long deadline = System.currentTimeMillis() + 5000;
        while (status.equals("PENDING_PAYMENT") && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            status = jdbc.sql("SELECT status FROM booking WHERE id = :id").param("id", booking.id()).query(String.class).single();
        }
        assertThat(status).isEqualTo("EXPIRED");
        assertThat(rows("seat_reservation")).isZero();
    }
}
