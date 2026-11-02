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
@SpringBootTest(properties = {
        "trainbooking.internal-api-key=" + DatabaseTest.INTERNAL_KEY,
        "trainbooking.ticket.signing-key=" + DatabaseTest.SIGNING_KEY,
        "trainbooking.timezone=Africa/Cairo"
})
@AutoConfigureMockMvc
@Import(DatabaseTestConfig.class)
@RequiresTestDatabase
public abstract class DatabaseTest {

    public static final String INTERNAL_KEY = "test-internal-key";
    public static final String SIGNING_KEY = "test-signing-key-0123456789";

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

    @BeforeEach
    void emptyTheTestSchema() {
        String schema = jdbc.sql("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual").query(String.class).single();
        assertThat(schema).as("database tests must run on a *_test schema").endsWithIgnoringCase("_test");
        jdbc.sql("DELETE FROM ticket").update();
        jdbc.sql("DELETE FROM audit_log").update();
        clock.set(MutableClock.START);
    }

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
