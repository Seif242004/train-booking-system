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

    /** The active hold (PENDING_PAYMENT) of a user on a trip, if there is one: what the seat map shows as "selected". */
    @Transactional(readOnly = true)
    public java.util.Optional<BookingView> activeHold(String userId, long tripId) {
        return bookings.findActiveHold(userId, tripId).map(this::toView);
    }

    @Transactional(readOnly = true)
    public BookingView view(long id) {
        return toView(bookings.find(id).orElseThrow(() -> new BookingNotFoundException(id)));
    }

    /** "My bookings": the caller's holds, confirmed and cancelled bookings, newest first, each with whether it can still be cancelled. */
    @Transactional(readOnly = true)
    public java.util.List<com.trainbooking.bookingseat.booking.model.MyBooking> listOwn(String userId) {
        return bookings.findOfUser(userId, 50).stream().map(booking -> {
            BookingView view = toView(booking);
            // A counter or complimentary booking is cancelled at the support desk, never by the passenger (docs/design/COUNTER-BOOKING.md, 7).
            boolean cancellable = booking.status() == com.trainbooking.bookingseat.booking.model.BookingStatus.CONFIRMED && !booking.paymentMethod().issuedByAdmin()
                    && view.trip().from().departureTime() != null && view.trip().from().departureTime().toInstant().isAfter(clock.instant());
            return new com.trainbooking.bookingseat.booking.model.MyBooking(view, cancellable);
        }).toList();
    }

    public BookingView toView(Booking booking) {
        BookingView.TripInfo trip = bookings.findTripInfo(booking.tripId(), booking.fromStop(), booking.toStop()).orElseThrow();
        return new BookingView(booking.id(), booking.reference(), booking.status(), booking.refundStatus(), booking.tripId(), booking.fromStop(), booking.toStop(),
                booking.amount(), booking.currency(), booking.expiresAt(), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), booking.createdAt(),
                trip, bookings.findItems(booking.id()), booking.paymentMethod());
    }
}
