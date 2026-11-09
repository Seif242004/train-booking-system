package com.trainbooking.notification.notification.service;

import com.trainbooking.notification.mail.EmailSender;
import com.trainbooking.notification.notification.model.Email;
import com.trainbooking.notification.notification.repository.EmailRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends the stored emails (CLAUDE.md: tickets and emails are asynchronous with retries). Each due email is sent outside any database
 * transaction; success marks it SENT, a failure schedules another try with a backoff (2, 4 ... 300 seconds), and after
 * {@value #MAX_ATTEMPTS} attempts the email is marked FAILED and logged as an error. Nothing here touches a booking.
 */
// @Component: a bean shared with the scheduler (EmailScheduler) and the tests, which call dispatchDue() by hand.
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    /** About a day of retries with the backoff capped at five minutes. */
    // 300 tries with the wait capped at 300 s is roughly a day of trying.
    static final int MAX_ATTEMPTS = 300;
    private static final long MAX_BACKOFF_SECONDS = 300;

    private final EmailRepository emails;
    private final EmailSender sender;
    private final Clock clock;

    public EmailDispatcher(EmailRepository emails, EmailSender sender, Clock clock) {
        this.emails = emails;
        this.sender = sender;
        this.clock = clock;
    }

    /** @return how many emails were sent */
    /*
     * One pass of the sender. Takes up to 20 due ids, loads each email, sends it, then records the outcome. The mail server is called with
     * no transaction open, so a slow server holds no database connection or lock. One failing email does not stop the others (continue).
     * Concurrency: one scheduler thread runs this at a time (fixedDelay), and markSent only succeeds while the row is still PENDING.
     */
    public int dispatchDue() {
        int sent = 0;
        for (long id : emails.findDueIds(now(), 20)) {
            Email email = emails.find(id).orElse(null);
            if (email == null) {
                continue;
            }
            try {
                sender.send(email);
            } catch (RuntimeException e) {
                if (email.attempts() + 1 >= MAX_ATTEMPTS) {
                    log.error("Email {} ({} for booking {}) could not be sent after {} attempts: giving up", id, email.kind(), email.reference(), MAX_ATTEMPTS);
                    emails.markFailed(id, e.getMessage());
                } else {
                    // Backoff: 2, 4, 8 ... seconds (a power of two that grows with the attempt count, shift capped at 9 = 512) and never
                    // more than 300.
                    long wait = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(email.attempts() + 1, 9));
                    log.warn("Email {} ({} for booking {}) not sent, next try in {} s: {}", id, email.kind(), email.reference(), wait, e.getMessage());
                    emails.scheduleRetry(id, now().plusSeconds(wait), e.getMessage());
                }
                continue;
            }
            // Marked sent only after the mail server took it. If the process dies between the two, the email goes out twice, which is
            // better than never (the ticket is the same ticket).
            if (emails.markSent(id, now())) {
                sent++;
            }
        }
        return sent;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
