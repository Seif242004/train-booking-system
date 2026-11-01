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
@Component
public class BookingInventory {

    private final InventoryRepository inventory;
    private final AvailabilityEvents events;

    public BookingInventory(InventoryRepository inventory, AvailabilityEvents events) {
        this.inventory = inventory;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
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
