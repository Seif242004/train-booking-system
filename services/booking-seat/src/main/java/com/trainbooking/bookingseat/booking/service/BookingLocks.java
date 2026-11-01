package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.booking.repository.InventoryRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one lock order of every transaction that changes the inventory of a trip: <b>first the row of the trip, then the row of the
 * booking, then the seat and standing rows, then the version counter</b>. A hold, a release, a confirmation, a cancellation and the
 * snapshot receiver all follow it, so two of them can wait for each other but can never wait in a circle (ORA-00060).
 * A hold takes the trip row first by itself; the others call this.
 */
@Component
public class BookingLocks {

    private final BookingRepository bookings;
    private final InventoryRepository inventory;

    public BookingLocks(BookingRepository bookings, InventoryRepository inventory) {
        this.bookings = bookings;
        this.inventory = inventory;
    }

    /** Locks the trip of the booking and then the booking itself, and returns the booking as it is now (empty if there is none). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Booking> lock(long bookingId) {
        Optional<Booking> unlocked = bookings.find(bookingId);
        if (unlocked.isEmpty()) {
            return Optional.empty();
        }
        inventory.lockTrip(unlocked.get().tripId());
        return bookings.findForUpdate(bookingId);
    }
}
