package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.repository.InventoryRepository;
import com.trainbooking.bookingseat.live.AvailabilityEvents;
import com.trainbooking.bookingseat.live.SeatEvent;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives the seats and standing places of a booking back: deletes its reservation rows and decrements the standing counters,
 * and announces it. The expiry of a hold, the manual release of a hold and the cancellation of a confirmed booking all use
 * this one implementation, in the transaction of their own change.
 */
// @Component: a plain bean used by HoldReleaseService and CancellationService.
@Component
public class BookingInventory {

    private final InventoryRepository inventory;
    private final AvailabilityEvents events;

    public BookingInventory(InventoryRepository inventory, AvailabilityEvents events) {
        this.inventory = inventory;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    /**
     * Returns everything the booking holds to the pool. MANDATORY propagation: it must run inside the caller's transaction (it throws
     * otherwise), so the seats are freed if and only if the status change in that same transaction commits.
     * Steps: read the seats and segments, delete the reservation rows, publish SEAT_RELEASED, then give back each standing hold segment by
     * segment (ascending, the same order as when taken, to avoid deadlocks), publish STANDING_CHANGED and delete the standing_hold rows.
     */
    public void giveBack(Booking booking) {
        List<Long> seatIds = inventory.findReservedSeatIds(booking.id());
        List<Integer> segments = inventory.findReservedSegments(booking.id());
        inventory.deleteReservations(booking.id());
        events.seats(SeatEvent.SEAT_RELEASED, booking.tripId(), seatIds, segments);

        // The coaches are in coach order and the segments in ascending order, the same order as when they were taken.
        for (InventoryRepository.StandingHoldRow hold : inventory.findStandingHolds(booking.id())) {
            List<Integer> holdSegments = IntStream.rangeClosed(hold.fromSegment(), hold.toSegment()).boxed().toList();
            for (int segment : holdSegments) {
                inventory.releaseStandingSegment(booking.tripId(), hold.coachId(), segment, hold.qty());
            }
            events.standing(booking.tripId(), hold.coachId(), hold.qty(), holdSegments);
        }
        inventory.deleteStandingHolds(booking.id());
    }
}
