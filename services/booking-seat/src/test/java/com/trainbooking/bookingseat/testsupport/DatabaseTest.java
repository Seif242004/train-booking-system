package com.trainbooking.bookingseat.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base of the tests that run against a real Oracle schema. The whole application starts (Flyway migrates the schema on
 * startup) but connected to {@code booking_seat_test}, which is emptied before every test. A guard refuses to run
 * against any schema whose name does not end in {@code _test}, so these tests can never wipe the dev data.
 */
@SpringBootTest(properties = {
        "trainbooking.internal-api-key=" + DatabaseTest.INTERNAL_KEY,
        // Never contacted: the tests send mock JWTs. The issuer is only looked up when a real token has to be decoded.
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/not-used"
})
@AutoConfigureMockMvc
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

    @BeforeEach
    void emptyTheTestSchema() {
        String schema = jdbc.sql("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual").query(String.class).single();
        assertThat(schema).as("database tests must run on a *_test schema").endsWithIgnoringCase("_test");
        jdbc.sql("DELETE FROM seat_reservation").update();
        jdbc.sql("DELETE FROM trip").update(); // the copy of the trip (stops, coaches, seats, fares, standing) goes with it
        jdbc.sql("DELETE FROM audit_log").update();
    }

    // ---- fixtures: a small trip written with plain SQL, independent of the snapshot receiver ---------------------

    /**
     * A trip with 4 stops (segments 0 to 2); coach 10 FIRST with seat 1; coach 20 SECOND with seats 2 and 3 and
     * 10 standing places; coach 30 THIRD with seat 4 and 5 standing places. Ids are those of Train and Schedule.
     */
    protected void insertTrip(long tripId) {
        jdbc.sql("INSERT INTO trip (id, departure_date, train_number, train_name, train_type, route_name) "
                        + "VALUES (:id, DATE '2026-11-02', 'T100', 'Express', 'Express', 'Cairo-Alexandria')")
                .param("id", tripId).update();
        String[][] stops = {{"CAI", "Cairo"}, {"GIZ", "Giza"}, {"TAN", "Tanta"}, {"ALX", "Alexandria"}};
        for (int i = 0; i < stops.length; i++) {
            jdbc.sql("INSERT INTO trip_stop (trip_id, stop_index, station_id, station_code, station_name) "
                            + "VALUES (:t, :i, :station, :code, :name)")
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

    /** A confirmed booking of a seat on one segment (no expiry). */
    protected void book(long tripId, long seatId, int segment) {
        jdbc.sql("INSERT INTO seat_reservation (trip_id, seat_id, segment_index, booking_id, expires_at) VALUES (:t, :s, :seg, 1, NULL)")
                .param("t", tripId).param("s", seatId).param("seg", segment).update();
    }

    /** A hold of a seat on one segment, expiring in {@code minutes} (negative: it expired already and is not released yet). */
    protected void hold(long tripId, long seatId, int segment, int minutes) {
        jdbc.sql("INSERT INTO seat_reservation (trip_id, seat_id, segment_index, booking_id, expires_at) "
                        + "VALUES (:t, :s, :seg, 2, SYSTIMESTAMP + NUMTODSINTERVAL(:minutes, 'MINUTE'))")
                .param("t", tripId).param("s", seatId).param("seg", segment).param("minutes", minutes).update();
    }

    protected void useStanding(long tripId, long coachId, int segment, int used) {
        jdbc.sql("UPDATE standing_usage SET used = :used WHERE trip_id = :t AND coach_id = :c AND segment_index = :seg")
                .param("t", tripId).param("c", coachId).param("seg", segment).param("used", used).update();
    }
}
