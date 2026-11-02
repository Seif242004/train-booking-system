package com.trainbooking.bookingseat.outbox.service;

import com.trainbooking.bookingseat.outbox.model.OutboxMessage;
import com.trainbooking.bookingseat.outbox.repository.OutboxRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues the jobs for Ticket and Notification. It must run inside the transaction of the change that causes the job (MANDATORY), so the
 * change and its job are saved together or not at all: a confirmed booking always gets its ticket email, and a rolled-back
 * confirmation never sends one.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxWriter(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /** @return the id of the job */
    @Transactional(propagation = Propagation.MANDATORY)
    public long queue(OutboxMessage.Kind kind, long bookingId) {
        return outbox.insert(kind, bookingId, null, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /** Queues the ticket email unless one is already waiting (a resend while the first is still queued adds nothing). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean queueTicketEmailUnlessPending(long bookingId) {
        if (outbox.hasPending(bookingId, OutboxMessage.Kind.TICKET_EMAIL)) {
            return false;
        }
        queue(OutboxMessage.Kind.TICKET_EMAIL, bookingId);
        return true;
    }
}
