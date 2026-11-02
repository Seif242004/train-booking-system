package com.trainbooking.notification.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.notification.notification.model.Email;
import com.trainbooking.notification.notification.model.EmailStatus;
import com.trainbooking.notification.notification.repository.EmailRepository;
import com.trainbooking.notification.notification.service.EmailDispatcher;
import com.trainbooking.notification.notification.service.NotificationService;
import com.trainbooking.notification.testsupport.DatabaseTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The email outbox: a request is stored and answered at once; a poller sends it with retries; and nothing about the mail server can fail
 * the caller (CLAUDE.md: an email failure never cancels a booking).
 */
class NotificationDatabaseTest extends DatabaseTest {

    @Autowired
    NotificationService service;
    @Autowired
    EmailDispatcher dispatcher;
    @Autowired
    EmailRepository emails;

    private String statusOf(String key) {
        return emails.findByDedupeKey(key).orElseThrow().status().name();
    }

    // ---- accepting ---------------------------------------------------------------------------------------------------

    @Test
    void aRequest_isStoredPending_withTheTextAndTheAttachment_andNothingIsSentYet() {
        NotificationService.Accepted accepted = service.accept(ticketEmail("ticket:7:1"));

        assertThat(accepted.created()).isTrue();
        Email email = emails.find(accepted.email().id()).orElseThrow();
        assertThat(email.status()).isEqualTo(EmailStatus.PENDING);
        assertThat(email.subject()).isEqualTo("Your train ticket TB-ABC234");
        assertThat(email.recipient()).isEqualTo("alice@example.com");
        assertThat(email.bodyText()).contains("TB-ABC234");
        assertThat(email.attachmentName()).isEqualTo("ticket-TB-ABC234.pdf");
        assertThat(new String(email.attachment())).isEqualTo("%PDF-1.4 fake");
        assertThat(mailServer.attempts()).isZero(); // the caller's answer never waits for the mail server
    }

    @Test
    void theSameKeyTwice_isOneEmail() {
        service.accept(ticketEmail("ticket:7:1"));
        NotificationService.Accepted again = service.accept(ticketEmail("ticket:7:1"));

        assertThat(again.created()).isFalse();
        assertThat(rows("email")).isEqualTo(1);
        service.accept(ticketEmail("ticket:7:2")); // a resend has its own key
        assertThat(rows("email")).isEqualTo(2);
    }

    @Test
    void twoRequestsWithTheSameKeyAtTheSameMoment_storeOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return service.accept(ticketEmail("ticket:7:1"));
            }));
        }
        go.countDown();
        for (Future<?> result : results) {
            result.get();
        }
        pool.shutdown();

        assertThat(rows("email")).isEqualTo(1);
    }

    // ---- sending -----------------------------------------------------------------------------------------------------

    @Test
    void thePoller_sendsTheEmail_once_andMarksItSent() {
        service.accept(ticketEmail("ticket:7:1"));

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(dispatcher.dispatchDue()).isZero(); // not sent again

        assertThat(mailServer.sent()).singleElement().satisfies(email -> {
            assertThat(email.recipient()).isEqualTo("alice@example.com");
            assertThat(new String(email.attachment())).startsWith("%PDF");
        });
        assertThat(statusOf("ticket:7:1")).isEqualTo("SENT");
        assertThat(emails.findByDedupeKey("ticket:7:1").orElseThrow().sentAt()).isNotNull();
    }

    @Test
    void whenTheMailServerIsDown_theEmailWaits_withABackoff_andGoesOutWhenItIsBack() {
        service.accept(ticketEmail("ticket:7:1"));
        mailServer.down(true);

        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(statusOf("ticket:7:1")).isEqualTo("PENDING"); // nothing is lost
        assertThat(mailServer.attempts()).isEqualTo(1);

        dispatcher.dispatchDue();                               // the same second: not due yet, not tried again
        assertThat(mailServer.attempts()).isEqualTo(1);

        mailServer.down(false);
        clock.advance(Duration.ofSeconds(10));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(statusOf("ticket:7:1")).isEqualTo("SENT");
    }

    @Test
    void theBackoffGrows_andIsCappedAtFiveMinutes() {
        service.accept(ticketEmail("ticket:7:1"));
        mailServer.down(true);

        for (int round = 0; round < 12; round++) {
            clock.advance(Duration.ofMinutes(10));
            dispatcher.dispatchDue();
        }

        assertThat(emails.findByDedupeKey("ticket:7:1").orElseThrow().attempts()).isEqualTo(12);
        long waitSeconds = jdbc.sql("SELECT (CAST(next_attempt_at AS DATE) - CAST(TIMESTAMP '2026-11-01 10:00:00 +00:00' AS DATE)) * 86400 FROM email")
                .query(Long.class).single() - 12 * 600;
        assertThat(waitSeconds).isBetween(1L, 300L);
    }

    @Test
    void oneAddressThatIsRefused_doesNotBlockTheOthers() {
        service.accept(ticketEmail("ticket:7:1"));
        var other = ticketEmail("ticket:8:1");
        service.accept(new com.trainbooking.notification.notification.model.NotificationRequest(other.dedupeKey(), other.kind(), "bob@example.com", "Bob",
                "TB-BOB555", other.trip(), other.lines(), other.amount(), other.currency(), null, other.attachment()));
        mailServer.refuses(email -> email.recipient().startsWith("alice"));

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        assertThat(statusOf("ticket:7:1")).isEqualTo("PENDING");
        assertThat(statusOf("ticket:8:1")).isEqualTo("SENT");
    }

    @Test
    void afterAVeryLongTime_anEmailThatNeverGoesOut_isMarkedFailedAndNeverBlocksAnything() {
        service.accept(ticketEmail("ticket:7:1"));
        jdbc.sql("UPDATE email SET attempts = 299").update();
        mailServer.down(true);

        dispatcher.dispatchDue();

        assertThat(statusOf("ticket:7:1")).isEqualTo("FAILED");
        clock.advance(Duration.ofDays(1));
        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(mailServer.attempts()).isEqualTo(1);
    }

    // ---- the endpoint ------------------------------------------------------------------------------------------------

    private static final String BODY = """
            {"dedupeKey":"ticket:7:1","kind":"TICKET","to":"alice@example.com","toName":"Alice","reference":"TB-ABC234",
             "lines":["Seat 1A"],"amount":230.00,"currency":"EGP"}""";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String body) {
        return post("/internal/notifications").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void theEndpoint_needsTheKey() throws Exception {
        mvc.perform(request(BODY)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("invalid_api_key"));
        mvc.perform(request(BODY).header("X-Internal-Api-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(request(BODY).header("Authorization", "Bearer a.user.token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/notifications")).andExpect(status().isUnauthorized());
        assertThat(rows("email")).isZero();
    }

    @Test
    void theEndpoint_answers202_thenOk_forTheSameKey() throws Exception {
        mvc.perform(request(BODY).header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(request(BODY).header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isOk());
        assertThat(rows("email")).isEqualTo(1);
    }

    @Test
    void theEndpoint_validatesTheInput() throws Exception {
        String key = INTERNAL_KEY;
        mvc.perform(request("{}").header("X-Internal-Api-Key", key)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("validation_failed"));
        mvc.perform(request(BODY.replace("alice@example.com", "not-an-address")).header("X-Internal-Api-Key", key)).andExpect(status().isBadRequest());
        mvc.perform(request(BODY.replace("TICKET", "SPAM")).header("X-Internal-Api-Key", key)).andExpect(status().isBadRequest());
        mvc.perform(request(BODY.replace("\"dedupeKey\":\"ticket:7:1\"", "\"dedupeKey\":\"\"")).header("X-Internal-Api-Key", key)).andExpect(status().isBadRequest());
        mvc.perform(request("not json").header("X-Internal-Api-Key", key)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("malformed_request"));
        assertThat(rows("email")).isZero();
    }

    @Test
    void anythingElse_isClosed() throws Exception {
        mvc.perform(get("/notifications")).andExpect(status().is4xxClientError());
        mvc.perform(get("/internal/unknown").header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isNotFound());
    }
}
