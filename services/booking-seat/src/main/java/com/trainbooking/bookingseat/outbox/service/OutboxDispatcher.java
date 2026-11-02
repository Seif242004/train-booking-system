package com.trainbooking.bookingseat.outbox.service;

import com.trainbooking.bookingseat.outbox.model.OutboxMessage;
import com.trainbooking.bookingseat.outbox.repository.OutboxRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the jobs of the outbox (CLAUDE.md, "Async calls without a broker"), outside any transaction. A job that fails is tried again with a
 * backoff (2, 4 ... 300 seconds) and the failure is logged; after {@value #MAX_ATTEMPTS} attempts it is marked FAILED and logged as an
 * error. The jobs of one booking run in the order they were queued. Nothing here can change a booking: a ticket or an email that cannot be
 * made never cancels a booking (CLAUDE.md).
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    static final int MAX_ATTEMPTS = 300;
    private static final long MAX_BACKOFF_SECONDS = 300;

    private final OutboxRepository outbox;
    private final Map<OutboxMessage.Kind, OutboxJob> jobs = new EnumMap<>(OutboxMessage.Kind.class);
    private final Clock clock;

    public OutboxDispatcher(OutboxRepository outbox, List<OutboxJob> jobs, Clock clock) {
        this.outbox = outbox;
        jobs.forEach(job -> this.jobs.put(job.kind(), job));
        this.clock = clock;
    }

    /** @return how many jobs were done */
    public int dispatchDue() {
        int done = 0;
        for (OutboxMessage message : outbox.findDue(now(), 20)) {
            OutboxJob job = jobs.get(message.kind());
            try {
                if (job == null) {
                    throw new IllegalStateException("There is no job for " + message.kind());
                }
                job.run(message);
            } catch (RuntimeException e) {
                if (job != null) {
                    try {
                        job.onFailure(message, message.attempts() + 1, e);
                    } catch (RuntimeException hookFailure) {
                        log.error("The failure hook of outbox job {} failed: {}", message.id(), hookFailure.getMessage());
                    }
                }
                if (message.attempts() + 1 >= MAX_ATTEMPTS && !(job != null && job.retryForever())) {
                    log.error("Outbox job {} ({} for booking {}) failed {} times: giving up. {}", message.id(), message.kind(), message.bookingId(), MAX_ATTEMPTS, e.getMessage());
                    outbox.markFailed(message.id(), e.getMessage());
                } else {
                    long wait = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(message.attempts() + 1, 9));
                    log.warn("Outbox job {} ({} for booking {}) not done, next try in {} s: {}", message.id(), message.kind(), message.bookingId(), wait, e.getMessage());
                    outbox.scheduleRetry(message.id(), now().plusSeconds(wait), e.getMessage());
                }
                continue;
            }
            if (outbox.markSent(message.id(), now())) {
                done++;
            }
        }
        return done;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
