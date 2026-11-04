package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.booking.model.AdminBookingView;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.PaymentMethod;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.RequestValidationException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Support: look up bookings (Flow 7, FR-C6) by reference, user email, trip, status, or the ones whose refund failed. */
@Service
public class AdminBookingQueryService {

    /** More than this is not a list a person reads: narrow the search. */
    static final int LIMIT = 100;

    private final BookingRepository bookings;
    private final BookingQueryService views;
    private final Clock clock;

    public AdminBookingQueryService(BookingRepository bookings, BookingQueryService views, Clock clock) {
        this.bookings = bookings;
        this.views = views;
        this.clock = clock;
    }

    /** Every filter is optional and they combine (AND). The newest bookings come first. */
    @Transactional(readOnly = true)
    public List<AdminBookingView> search(String reference, String email, Long tripId, String status, String refundStatus) {
        return search(reference, email, tripId, status, refundStatus, null);
    }

    @Transactional(readOnly = true)
    public List<AdminBookingView> search(String reference, String email, Long tripId, String status, String refundStatus, String method) {
        PaymentMethod parsedMethod = parse(PaymentMethod.class, "method", method);
        BookingStatus parsedStatus = parse(BookingStatus.class, "status", status);
        RefundStatus parsedRefund = parse(RefundStatus.class, "refundStatus", refundStatus);
        return bookings.search(blankToNull(reference), blankToNull(email), tripId, parsedStatus, parsedRefund, parsedMethod, LIMIT).stream().map(this::toAdminView).toList();
    }

    @Transactional(readOnly = true)
    public AdminBookingView details(long id) {
        return toAdminView(bookings.find(id).orElseThrow(() -> new BookingNotFoundException(id)));
    }

    private AdminBookingView toAdminView(Booking booking) {
        BookingView view = views.toView(booking);
        boolean cancellable = booking.status() == BookingStatus.CONFIRMED && view.trip().from().departureTime() != null
                && view.trip().from().departureTime().toInstant().isAfter(clock.instant());
        return new AdminBookingView(view, booking.userId(), booking.userEmail(), booking.userName(), booking.confirmedAt() != null ? "PAID" : "NOT_PAID",
                booking.confirmedAt(), booking.cancelledAt(), booking.cancelledBy(), booking.cancelledByRole(), booking.cancelReason(), cancellable,
                booking.paymentMethod().issuedByAdmin() ? new AdminBookingView.Issue(booking.paymentMethod(), booking.chargedAmount(), booking.issuedBy(), booking.issuedAt(), booking.issueNote(),
                        booking.walkInName(), booking.walkInPhone(), booking.walkInEmail(), booking.counterRefundedBy(), booking.counterRefundedAt()) : null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RequestValidationException(List.of(new ApiError.Detail(field, "must be one of " + java.util.Arrays.toString(type.getEnumConstants()))));
        }
    }
}
