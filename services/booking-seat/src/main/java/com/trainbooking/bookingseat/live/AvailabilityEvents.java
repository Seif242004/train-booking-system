package com.trainbooking.bookingseat.live;

import com.trainbooking.bookingseat.booking.repository.InventoryRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announces changes of the availability of a trip. Every method must be called inside the transaction of the change itself
 * (MANDATORY): it takes the next version number of the trip there (so the counter is part of the same commit) and publishes the
 * event, which is delivered to the clients only after that transaction has committed (SRS section 6, CLAUDE.md "Live updates").
 */
@Component
public class AvailabilityEvents {

    private final InventoryRepository inventory;
    private final ApplicationEventPublisher publisher;

    public AvailabilityEvents(InventoryRepository inventory, ApplicationEventPublisher publisher) {
        this.inventory = inventory;
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void seats(String type, long tripId, Collection<Long> seatIds, List<Integer> segments) {
        if (seatIds.isEmpty()) {
            return;
        }
        publisher.publishEvent(new SeatEvent(type, tripId, List.copyOf(seatIds), null, null, List.copyOf(segments), inventory.nextVersion(tripId)));
    }

    /** @param delta the change of the standing places left in the coach: negative when places are taken, positive when given back */
    @Transactional(propagation = Propagation.MANDATORY)
    public void standing(long tripId, long coachId, int delta, List<Integer> segments) {
        publisher.publishEvent(new SeatEvent(SeatEvent.STANDING_CHANGED, tripId, List.of(), coachId, delta, List.copyOf(segments), inventory.nextVersion(tripId)));
    }

    /** The trip is cancelled: it applies to every segment, so the segment list is empty and means "all". */
    @Transactional(propagation = Propagation.MANDATORY)
    public void tripCancelled(long tripId) {
        publisher.publishEvent(new SeatEvent(SeatEvent.TRIP_CANCELLED, tripId, List.of(), null, null, List.of(), inventory.nextVersion(tripId)));
    }

    /** An event for one user; it has no version because it is not about the availability. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void toUser(String type, String userId, long tripId, long bookingId, String reason) {
        publisher.publishEvent(new UserEvent(type, userId, tripId, bookingId, reason));
    }
}
