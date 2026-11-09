package com.trainbooking.ticket.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.ticket.ticket.model.TicketRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
 * Base of the tests that run against a real Oracle schema: the whole application starts (Flyway migrates the schema) but connected to
 * {@code ticket_test}, which is emptied before every test. A guard refuses to run on any schema whose name does not end in {@code _test}.
 */
// {@code @SpringBootTest} starts the whole application (real beans, real Flyway, real Oracle) for the tests of the subclasses. The
// listed properties replace the settings that would otherwise come from the .env file: a fixed internal key, a fixed signing key and the
// Cairo zone. The context is cached, so it starts once for all the database test classes of a run.
@SpringBootTest(properties = {
        "trainbooking.internal-api-key=" + DatabaseTest.INTERNAL_KEY,
        "trainbooking.ticket.signing-key=" + DatabaseTest.SIGNING_KEY,
        "trainbooking.timezone=Africa/Cairo"
})
// {@code @AutoConfigureMockMvc} gives a MockMvc: it sends requests through the real filter chain (security, validation, handlers)
// without opening a network port.
@AutoConfigureMockMvc
// {@code @Import} adds the test configuration, which replaces the Clock with the one the tests can move.
@Import(DatabaseTestConfig.class)
// Our own annotation: skips the whole class when no test password is set.
@RequiresTestDatabase
public abstract class DatabaseTest {

    public static final String INTERNAL_KEY = "test-internal-key";
    public static final String SIGNING_KEY = "test-signing-key-0123456789";

    @DynamicPropertySource
    // {@code @DynamicPropertySource} lets the test fix the datasource settings at run time, so the tests always connect to ticket_test and
    // never to the dev schema.
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @Autowired
    // {@code @Autowired}: Spring fills the field with the bean of that type. Protected so the subclasses can run SQL checks.
    protected JdbcClient jdbc;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    // {@code @BeforeEach}: runs before every test. First the guard (the current schema must end in _test, otherwise the test fails before
    // deleting anything), then both tables are emptied and the clock goes back to its start. Each test therefore starts from a clean state.
    void emptyTheTestSchema() {
        String schema = jdbc.sql("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual").query(String.class).single();
        assertThat(schema).as("database tests must run on a *_test schema").endsWithIgnoringCase("_test");
        jdbc.sql("DELETE FROM ticket").update();
        jdbc.sql("DELETE FROM audit_log").update();
        clock.set(MutableClock.START);
    }

    // Counts rows of a table (with an optional WHERE) so tests can check what was stored.
    protected int rows(String tableAndWhere) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tableAndWhere).query(Integer.class).single();
    }

    /** A ticket of a booking with one seat in the first class and two standing places in the second, Cairo to Alexandria. */
    public static TicketRequest ticketRequest(String reference) {
        return new TicketRequest(reference, "Alice Example", "T100", "Express",
                "Cairo", OffsetDateTime.parse("2026-11-02T06:00:00Z"), "Alexandria", OffsetDateTime.parse("2026-11-02T08:45:00Z"),
                List.of(new TicketRequest.Line("SEAT", "A1", "FIRST", "1A", 1, new BigDecimal("230.00")),
                        new TicketRequest.Line("STANDING", "B2", "SECOND", null, 2, new BigDecimal("300.00"))),
                new BigDecimal("530.00"), "EGP");
    }
}
