package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The stop times of a booking carry the offset of the railway's time zone (trainbooking.timezone), not whatever offset the database driver
 * returns for its session. The web app shows the clock part of these times as it is, so a container running in UTC must not show 06:00 for a
 * train that leaves at 08:00 Cairo time. The zone is set to Tokyo here so that neither the stored offset (+02:00) nor the JVM's zone can give
 * the expected answer by accident.
 */
@TestPropertySource(properties = "trainbooking.timezone=Asia/Tokyo")
class BookingStopTimesDatabaseTest extends DatabaseTest {

    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice");

    @Autowired
    Holds holds;

    @Test
    void theStopTimesOfABooking_areInTheRailwaysZone_atTheSameInstant() {
        insertTrip(9801);

        BookingView hold = holds.hold(ALICE, new HoldRequest(9801L, 0, 3, List.of(2L), List.of()));

        var departure = hold.trip().from().departureTime();
        assertThat(departure.toInstant()).isEqualTo(Instant.parse("2026-11-02T06:00:00Z")); // 08:00 +02:00 as stored: the instant never changes
        assertThat(departure.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(departure.toString()).startsWith("2026-11-02T15:00");
        assertThat(hold.trip().to().arrivalTime().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(hold.trip().to().departureTime()).as("the last stop has no departure").isNull();
    }
}
