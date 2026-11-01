package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a booking for the API. A booking that belongs to someone else is "not found", so its existence is never revealed. */
@Service
public class BookingQueryService {

    private final BookingRepository bookings;
    private final Clock clock;

    public BookingQueryService(BookingRepository bookings, Clock clock) {
        this.bookings = bookings;
        this.clock = clock;
    }

    /** The booking of a user: 404 if there is none or if it is somebody else's. */
    @Transactional(readOnly = true)
    public BookingView viewOwn(long id, String userId) {
        Booking booking = bookings.find(id).filter(b -> b.userId().equals(userId)).orElseThrow(() -> new BookingNotFoundException(id));
        return toView(booking);
    }

    @Transactional(readOnly = true)
    public BookingView view(long id) {
        return toView(bookings.find(id).orElseThrow(() -> new BookingNotFoundException(id)));
    }

    public BookingView toView(Booking booking) {
        BookingView.TripInfo trip = bookings.findTripInfo(booking.tripId(), booking.fromStop(), booking.toStop()).orElseThrow();
        return new BookingView(booking.id(), booking.reference(), booking.status(), booking.refundStatus(), booking.tripId(), booking.fromStop(), booking.toStop(),
                booking.amount(), booking.currency(), booking.expiresAt(), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), booking.createdAt(),
                trip, bookings.findItems(booking.id()));
    }
}
