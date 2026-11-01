package com.trainbooking.bookingseat.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base of the tests that run against a real Oracle schema. The whole application starts (Flyway migrates the schema on
 * startup) but connected to {@code booking_seat_test}, which is emptied before every test. A guard refuses to run on any
 * schema whose name does not end in {@code _test}, so these tests can never wipe the dev data.
 *
 * <p>The sweeper is switched off (the tests call it by hand), the clock can be moved, and Payment is a fake.
 */
@SpringBootTest(properties = {
        "trainbooking.internal-api-key=" + DatabaseTest.INTERNAL_KEY,
        "trainbooking.sweeper.enabled=false",
        // Never contacted: the tests send mock JWTs. The issuer is only looked up when a real token has to be decoded.
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/not-used"
})
@AutoConfigureMockMvc
@Import(DatabaseTestConfig.class)
@RequiresTestDatabase
public abstract class DatabaseTest {

    public static final String INTERNAL_KEY = "test-internal-key";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected FakePaymentPort payment;

    @Autowired
    protected CommittedEvents committed;

    @BeforeEach
    void emptyTheTestSchema() {
        String schema = jdbc.sql("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual").query(String.class).single();
        assertThat(schema).as("database tests must run on a *_test schema").endsWithIgnoringCase("_test");
        jdbc.sql("DELETE FROM seat_reservation").update();
        jdbc.sql("DELETE FROM standing_hold").update();
        jdbc.sql("DELETE FROM booking_item").update();
        jdbc.sql("DELETE FROM booking").update();
        jdbc.sql("DELETE FROM trip").update(); // the copy of the trip (stops, coaches, seats, fares, standing, version) goes with it
        jdbc.sql("DELETE FROM audit_log").update();
        clock.set(MutableClock.START);
        payment.reset();
        committed.clear();
    }

    protected int rows(String tableAndWhere) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tableAndWhere).query(Integer.class).single();
    }

    // ---- fixtures: a small trip written with plain SQL, independent of the snapshot receiver ---------------------

    /**
     * A trip with 4 stops (segments 0 to 2) leaving Cairo on 2026-11-02 at 08:00 (+02:00) and one stop every hour;
     * coach 10 FIRST with seat 1; coach 20 SECOND with seats 2 and 3 and 10 standing places; coach 30 THIRD with seat 4 and
     * 5 standing places. Fares per segment: FIRST 10, SECOND 5, THIRD 3 (EGP). Ids are those of Train and Schedule.
     */
    protected void insertTrip(long tripId) {
        jdbc.sql("INSERT INTO trip (id, departure_date, train_number, train_name, train_type, route_name) "
                        + "VALUES (:id, DATE '2026-11-02', 'T100', 'Express', 'Express', 'Cairo-Alexandria')")
                .param("id", tripId).update();
        jdbc.sql("INSERT INTO trip_version (trip_id, version) VALUES (:id, 0)").param("id", tripId).update();
        String[][] stops = {{"CAI", "Cairo"}, {"GIZ", "Giza"}, {"TAN", "Tanta"}, {"ALX", "Alexandria"}};
        for (int i = 0; i < stops.length; i++) {
            jdbc.sql("INSERT INTO trip_stop (trip_id, stop_index, station_id, station_code, station_name, arrival_time, departure_time) "
                            + "VALUES (:t, :i, :station, :code, :name, "
                            + (i == 0 ? "NULL" : "TIMESTAMP '2026-11-02 08:00:00 +02:00' + NUMTODSINTERVAL(" + i + ", 'HOUR') - NUMTODSINTERVAL(5, 'MINUTE')") + ", "
                            + (i == stops.length - 1 ? "NULL" : "TIMESTAMP '2026-11-02 08:00:00 +02:00' + NUMTODSINTERVAL(" + i + ", 'HOUR')") + ")")
                    .param("t", tripId).param("i", i).param("station", 100 + i).param("code", stops[i][0]).param("name", stops[i][1])
                    .update();
        }
        coach(tripId, 10, "A", "FIRST", 1);
        coach(tripId, 20, "B", "SECOND", 2);
        coach(tripId, 30, "C", "THIRD", 3);
        seat(tripId, 1, 10, "A1", 1, "A", "FIRST");
        seat(tripId, 2, 20, "B1", 1, "A", "SECOND");
        seat(tripId, 3, 20, "B2", 1, "B", "SECOND");
        seat(tripId, 4, 30, "C1", 1, "A", "THIRD");
        for (int segment = 0; segment < 3; segment++) {
            standing(tripId, 20, segment, 10, 0);
            standing(tripId, 30, segment, 5, 0);
            fare(tripId, segment, "FIRST", 10);
            fare(tripId, segment, "SECOND", 5);
            fare(tripId, segment, "THIRD", 3);
        }
    }

    protected void coach(long tripId, long coachId, String code, String coachClass, int position) {
        jdbc.sql("INSERT INTO trip_coach (trip_id, coach_id, code, class, position) VALUES (:t, :c, :code, :class, :pos)")
                .param("t", tripId).param("c", coachId).param("code", code).param("class", coachClass).param("pos", position).update();
    }

    protected void seat(long tripId, long seatId, long coachId, String number, int row, String column, String coachClass) {
        jdbc.sql("INSERT INTO trip_seat (trip_id, seat_id, coach_id, seat_number, seat_row, seat_column, class) "
                        + "VALUES (:t, :s, :c, :number, :row, :col, :class)")
                .param("t", tripId).param("s", seatId).param("c", coachId).param("number", number).param("row", row)
                .param("col", column).param("class", coachClass).update();
    }

    protected void standing(long tripId, long coachId, int segment, int capacity, int used) {
        jdbc.sql("INSERT INTO standing_usage (trip_id, coach_id, segment_index, capacity, used) VALUES (:t, :c, :seg, :cap, :used)")
                .param("t", tripId).param("c", coachId).param("seg", segment).param("cap", capacity).param("used", used).update();
    }

    protected void fare(long tripId, int segment, String coachClass, int amount) {
        jdbc.sql("INSERT INTO trip_fare (trip_id, segment_index, class, amount, currency) VALUES (:t, :seg, :class, :amount, 'EGP')")
                .param("t", tripId).param("seg", segment).param("class", coachClass).param("amount", amount).update();
    }

    /** A booking row to hang reservations on (the fixtures use ids 1 for a confirmed booking and 2 for a hold). */
    protected void ensureBooking(long tripId, long bookingId, String status) {
        jdbc.sql("MERGE INTO booking b USING (SELECT :id AS id FROM dual) s ON (b.id = s.id) "
                        + "WHEN NOT MATCHED THEN INSERT (id, reference, user_id, trip_id, from_stop, to_stop, status, amount, expires_at, created_at) "
                        + "VALUES (:id, 'TB-FIX' || :id, 'fixture-user', :trip, 0, 1, :status, 0, SYSTIMESTAMP + INTERVAL '1' DAY, SYSTIMESTAMP)")
                .param("id", bookingId).param("trip", tripId).param("status", status).update();
    }

    /** A confirmed booking of a seat on one segment (no expiry). */
    protected void book(long tripId, long seatId, int segment) {
        ensureBooking(tripId, 1, "CONFIRMED");
        jdbc.sql("INSERT INTO seat_reservation (trip_id, seat_id, segment_index, booking_id, expires_at) VALUES (:t, :s, :seg, 1, NULL)")
                .param("t", tripId).param("s", seatId).param("seg", segment).update();
    }

    /** A hold of a seat on one segment, expiring in {@code minutes} (negative: it expired already and is not released yet). */
    protected void hold(long tripId, long seatId, int segment, int minutes) {
        ensureBooking(tripId, 2, "PENDING_PAYMENT");
        jdbc.sql("INSERT INTO seat_reservation (trip_id, seat_id, segment_index, booking_id, expires_at) "
                        + "VALUES (:t, :s, :seg, 2, SYSTIMESTAMP + NUMTODSINTERVAL(:minutes, 'MINUTE'))")
                .param("t", tripId).param("s", seatId).param("seg", segment).param("minutes", minutes).update();
    }

    protected void useStanding(long tripId, long coachId, int segment, int used) {
        jdbc.sql("UPDATE standing_usage SET used = :used WHERE trip_id = :t AND coach_id = :c AND segment_index = :seg")
                .param("t", tripId).param("c", coachId).param("seg", segment).param("used", used).update();
    }
}
