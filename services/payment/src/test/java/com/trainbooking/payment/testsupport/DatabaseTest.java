package com.trainbooking.payment.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Base of the tests that run against a real Oracle schema. The whole application starts (Flyway migrates the schema) but connected to
 * {@code payment_test}, which is emptied before every test. A guard refuses to run on any schema whose name does not end in
 * {@code _test}. The schedulers are off (tests call them by hand), Stripe and Booking are fakes, and the clock can be moved.
 */
// @SpringBootTest starts the whole application (all beans, Flyway, security) for the test; "properties" override settings only for these
// tests: a known internal key, the outbox scheduler off (tests call the loops by hand) and fake Stripe keys. @AutoConfigureMockMvc gives
// a MockMvc that calls the controllers in-process, without a network port. @Import adds the fake beans of DatabaseTestConfig.
// @RequiresTestDatabase skips the class when no test database password is set.
@SpringBootTest(properties = {
        "trainbooking.internal-api-key=" + DatabaseTest.INTERNAL_KEY,
        "trainbooking.outbox.enabled=false",
        "stripe.publishable-key=pk_test_fake",
        "stripe.webhook-secret=" + DatabaseTest.WEBHOOK_SECRET
})
@AutoConfigureMockMvc
@Import(DatabaseTestConfig.class)
@RequiresTestDatabase
public abstract class DatabaseTest {

    public static final String INTERNAL_KEY = "test-internal-key";
    public static final String WEBHOOK_SECRET = "whsec_test_secret";

    // @DynamicPropertySource sets the datasource properties at run time from TestDatabase (the payment_test schema), after reading the
    // .env files.
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
    protected FakeStripeGateway stripe;

    @Autowired
    protected FakeBookingNotifier booking;

    // @BeforeEach runs before every test: it checks the schema name ends in _test (a guard against wiping a real schema), empties all
    // tables child-first (refund references payment), and resets the clock and the fakes, so each test starts from nothing.
    @BeforeEach
    void emptyTheTestSchema() {
        String schema = jdbc.sql("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual").query(String.class).single();
        assertThat(schema).as("database tests must run on a *_test schema").endsWithIgnoringCase("_test");
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("DELETE FROM payment").update();
        jdbc.sql("DELETE FROM webhook_event").update();
        jdbc.sql("DELETE FROM audit_log").update();
        clock.set(MutableClock.START);
        stripe.reset();
        booking.reset();
    }

    /** Delivers an event to the webhook, signed with the webhook secret at the current time of the test clock. */
    protected org.springframework.test.web.servlet.ResultActions deliver(String payload) throws Exception {
        return deliver(payload, signature(payload, WEBHOOK_SECRET, clock.instant()));
    }

    protected org.springframework.test.web.servlet.ResultActions deliver(String payload, String signatureHeader) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/webhooks/payment")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(payload.getBytes(StandardCharsets.UTF_8));
        if (signatureHeader != null) {
            request.header("Stripe-Signature", signatureHeader);
        }
        return mvc.perform(request);
    }

    // Counts rows of a table, optionally with a WHERE clause (test-only helper; the argument is a literal written in the test).
    protected int rows(String tableAndWhere) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tableAndWhere).query(Integer.class).single();
    }

    /** Stripe's signature header for a payload at a time: {@code t=<seconds>,v1=<HMAC-SHA256 of "t.payload" with the webhook secret>}. */
    protected static String signature(String payload, String secret, Instant at) {
        try {
            String signed = at.getEpochSecond() + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "t=" + at.getEpochSecond() + ",v1=" + HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
