package com.trainbooking.bookingseat.live;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers the events to the browsers, and only <b>after the database transaction that made them has committed</b> (SRS section 6,
 * CLAUDE.md "Live updates are pushed after the database commit, never before"): a client never sees a state that was rolled back.
 * An event that was published in a transaction that rolled back is never delivered. There is deliberately no fallback for events
 * published outside a transaction ({@link AvailabilityEvents} only publishes inside one).
 * Commits of different transactions can finish in a different order than their version numbers; a client that sees a gap in the
 * numbers loads the seat map again.
 */
// @Component: a bean. Spring calls the listener methods below whenever an event object of that type is published.
@Component
public class LiveUpdates {

    private final SimpMessagingTemplate messaging;

    public LiveUpdates(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    // @TransactionalEventListener(AFTER_COMMIT): the method runs only after the publishing transaction has committed; if it rolls back, the
    // event is dropped. This is the rule "live updates are pushed after the commit, never before".
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void seatEvent(SeatEvent event) {
        // Sends to every subscriber of the trip topic; the in-memory STOMP broker serialises the event as JSON.
        messaging.convertAndSend("/topic/trips/" + event.tripId(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void userEvent(UserEvent event) {
        // Sends only to the connection whose name (the user's sub) equals userId, on the destination /user/queue/holds.
        messaging.convertAndSendToUser(event.userId(), "/queue/holds", event);
    }
}
