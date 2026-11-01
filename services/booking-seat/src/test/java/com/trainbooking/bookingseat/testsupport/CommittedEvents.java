package com.trainbooking.bookingseat.testsupport;

import com.trainbooking.bookingseat.live.SeatEvent;
import com.trainbooking.bookingseat.live.UserEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Collects the events exactly as a real listener would get them: only after the transaction that published them has committed.
 * A rolled-back hold must leave nothing here (CLAUDE.md, "Live updates are pushed after the database commit, never before").
 */
public class CommittedEvents {

    private final List<SeatEvent> seatEvents = Collections.synchronizedList(new ArrayList<>());
    private final List<UserEvent> userEvents = Collections.synchronizedList(new ArrayList<>());

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSeatEvent(SeatEvent event) {
        seatEvents.add(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserEvent(UserEvent event) {
        userEvents.add(event);
    }

    public List<SeatEvent> seatEvents() {
        return List.copyOf(seatEvents);
    }

    public List<UserEvent> userEvents() {
        return List.copyOf(userEvents);
    }

    public void clear() {
        seatEvents.clear();
        userEvents.clear();
    }
}
