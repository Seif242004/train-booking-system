package com.trainbooking.notification.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.notification.notification.model.NotificationRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Base64;
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
 * {@code notification_test}, which is emptied before every test. The sender is a fake and the scheduler is off, so a test decides when
 * an email is sent and whether the "mail server" works.
 */
@SpringBootTest(properties = {
        "trainbooking.internal-api-key=" + DatabaseTest.INTERNAL_KEY,
        "trainbooking.notification.enabled=false",
        "trainbooking.timezone=Africa/Cairo"
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
    protected FakeEmailSender mailServer;

    @BeforeEach
    void emptyTheTestSchema() {
        String schema = jdbc.sql("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual").query(String.class).single();
        assertThat(schema).as("database tests must run on a *_test schema").endsWithIgnoringCase("_test");
        jdbc.sql("DELETE FROM email").update();
        jdbc.sql("DELETE FROM audit_log").update();
        clock.set(MutableClock.START);
        mailServer.reset();
    }

    protected int rows(String tableAndWhere) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tableAndWhere).query(Integer.class).single();
    }

    public static NotificationRequest ticketEmail(String key) {
        return new NotificationRequest(key, NotificationRequest.Kind.TICKET, "alice@example.com", "Alice Example", "TB-ABC234",
                new NotificationRequest.Trip("T100 Express", "Cairo", OffsetDateTime.parse("2026-11-02T06:00:00Z"), "Alexandria", OffsetDateTime.parse("2026-11-02T08:45:00Z")),
                List.of("Seat 1A, coach A1 (first): 230.00 EGP"), new BigDecimal("230.00"), "EGP", null,
                new NotificationRequest.Attachment("ticket-TB-ABC234.pdf", "application/pdf", Base64.getEncoder().encodeToString("%PDF-1.4 fake".getBytes())));
    }
}
