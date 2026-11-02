package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldConflictException;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.service.ConfirmationService;
import com.trainbooking.bookingseat.booking.service.ExpiryService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.payment.PaymentPort.CancelOutcome;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Things that happen at the same moment on real threads and a real database: the sweeper releasing holds while new holds come in
 * (the order of the locks, ORA-00060), and a payment being confirmed while the sweeper releases the same hold.
 */
class ConcurrencyDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9301;

    @Autowired
    com.trainbooking.bookingseat.testsupport.Holds holds;

    @Autowired
    ExpiryService expiry;

    @Autowired
    ConfirmationService confirmations;

    @BeforeEach
    void aTrip() {
        insertTrip(TRIP);
    }

    private static AuthenticatedUser user(String name) {
        return new AuthenticatedUser(name + "-sub", name + "@example.com", name);
    }

    private int usedStanding() {
        return jdbc.sql("SELECT SUM(used) FROM standing_usage WHERE trip_id = :t AND coach_id = 30").param("t", TRIP).query(Integer.class).single();
    }

    private void resetInventory() {
        jdbc.sql("DELETE FROM seat_reservation").update();
        jdbc.sql("DELETE FROM standing_hold").update();
        jdbc.sql("DELETE FROM booking_item").update();
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM booking").update();
        jdbc.sql("UPDATE standing_usage SET used = 0").update();
        clock.set(com.trainbooking.bookingseat.testsupport.MutableClock.START);
    }

    @Test
    void theSweeperReleasingHoldsWhileNewHoldsComeIn_neverDeadlocks_andTheCountersStayExact() throws Exception {
        for (int round = 0; round < 12; round++) {
            resetInventory();
            // Three holds, each a seat and one standing place in coach 30 (capacity 5): they are about to run out.
            for (int i = 0; i < 3; i++) {
                holds.hold(user("old" + i), new HoldRequest(TRIP, 0, 3, List.of((long) i + 1), List.of(new HoldRequest.StandingRequest(30L, 1))));
            }
            clock.advance(Duration.ofMinutes(5).plusSeconds(1));
            payment.alwaysAnswers(CancelOutcome.CANCELLED);

            AtomicInteger newHolds = new AtomicInteger();
            List<Callable<Object>> calls = new ArrayList<>();
            CountDownLatch go = new CountDownLatch(1);
            calls.add(() -> {
                go.await();
                expiry.sweep(); // releases the three old holds: seats, standing places and version, all in the one lock order
                return null;
            });
            for (int i = 0; i < 4; i++) {
                AuthenticatedUser fresh = user("fresh" + i);
                calls.add(() -> {
                    go.await();
                    try {
                        // A new hold on standing places of the same coach and the same segments the sweeper gives back.
                        holds.hold(fresh, new HoldRequest(TRIP, 0, 3, List.of(), List.of(new HoldRequest.StandingRequest(30L, 1))));
                        newHolds.incrementAndGet();
                    } catch (HoldConflictException expectedWhenFull) {
                        // fine: the answer to a full coach is a 409, never a failure of the database
                    }
                    return null;
                });
            }
            run(calls, go);

            // No deadlock: every old hold is released, and no thread failed with anything but a conflict.
            assertThat(rows("booking WHERE status = 'EXPIRED'")).as("round %d", round).isEqualTo(3);
            assertThat(rows("seat_reservation")).as("round %d", round).isZero();
            // What is left in the counters is exactly the new holds that succeeded.
            assertThat(usedStanding()).as("round %d", round).isEqualTo(newHolds.get() * 3); // 3 segments each
        }
    }

    @Test
    void aPaymentConfirmedWhileTheHoldIsBeingReleased_endsInOneOfTwoConsistentStates_neverInBetween() throws Exception {
        for (int round = 0; round < 15; round++) {
            resetInventory();
            BookingView booking = holds.hold(user("alice"), new HoldRequest(TRIP, 0, 3, List.of(1L), List.of(new HoldRequest.StandingRequest(30L, 2))));
            clock.advance(Duration.ofMinutes(5).plusSeconds(1));
            payment.alwaysAnswers(CancelOutcome.CANCELLED); // the sweeper's own call: Stripe cancelled the payment

            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Object>> calls = List.of(
                    () -> { go.await(); expiry.expire(booking.id()); return null; },
                    () -> { go.await(); return confirmations.confirm(booking.id()); });
            run(calls, go);

            String status = jdbc.sql("SELECT status FROM booking WHERE id = :id").param("id", booking.id()).query(String.class).single();
            if (status.equals("CONFIRMED")) {
                assertThat(rows("seat_reservation WHERE expires_at IS NULL")).as("round %d", round).isEqualTo(3);
                assertThat(usedStanding()).as("round %d", round).isEqualTo(6); // 2 places x 3 segments, still taken
            } else {
                assertThat(status).as("round %d", round).isEqualTo("EXPIRED");
                assertThat(rows("seat_reservation")).as("round %d", round).isZero();
                assertThat(usedStanding()).as("round %d", round).isZero();
            }
        }
    }

    private void run(List<Callable<Object>> calls, CountDownLatch go) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(call));
            }
            go.countDown();
            for (Future<Object> future : futures) {
                future.get(); // an exception of any thread fails the test here
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
