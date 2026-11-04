package com.trainbooking.bookingseat.booking.service;

import com.trainbooking.bookingseat.audit.AuditTrail;
import com.trainbooking.bookingseat.booking.model.Booking;
import com.trainbooking.bookingseat.booking.model.BookingConflictException;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingStatus;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.IssueRequest;
import com.trainbooking.bookingseat.booking.model.PassengerNotFoundException;
import com.trainbooking.bookingseat.booking.model.PaymentMethod;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.repository.BookingRepository;
import com.trainbooking.bookingseat.booking.repository.BookingRepository.KnownPassenger;
import com.trainbooking.bookingseat.common.ApiError;
import com.trainbooking.bookingseat.common.RequestValidationException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An admin confirms their own active hold WITHOUT a Stripe payment: the passenger paid at the counter, or the booking is complimentary
 * (docs/design/COUNTER-BOOKING.md). The confirmation itself is the one of the card path ({@link ConfirmationService#confirmLocked}): the same
 * HELD to CONFIRMED transition, the same event after commit, the same ticket and email job. Nothing here calls Payment or Stripe.
 */
@Service
public class BookingIssueService {

    private static final int MIN_REASON = 5;

    private final BookingRepository bookings;
    private final BookingLocks locks;
    private final ConfirmationService confirmation;
    private final BookingQueryService views;
    private final AuditTrail audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BookingIssueService(BookingRepository bookings, BookingLocks locks, ConfirmationService confirmation, BookingQueryService views, AuditTrail audit,
                               TransactionTemplate tx, Clock clock) {
        this.bookings = bookings;
        this.locks = locks;
        this.confirmation = confirmation;
        this.views = views;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /** The one passenger whose email is exactly this one, or empty. Never a list, never a partial match. */
    public Optional<KnownPassenger> lookup(String email) {
        if (email == null || email.isBlank() || email.length() > 254) {
            return Optional.empty();
        }
        List<KnownPassenger> found = bookings.findPassengersByEmail(email.trim());
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty(); // two accounts with one email would be ambiguous: no answer
    }

    public BookingView issue(String adminId, long bookingId, IssueRequest request) {
        validate(request);
        try {
            tx.executeWithoutResult(status -> issueInTransaction(adminId, bookingId, request));
        } catch (org.springframework.dao.PessimisticLockingFailureException e) {
            throw new BookingConflictException("busy", "Another change to this booking is in progress. Try again in a moment.");
        }
        return views.view(bookingId);
    }

    private static void validate(IssueRequest request) {
        List<ApiError.Detail> problems = new ArrayList<>();
        if (request.method() == PaymentMethod.CARD) {
            problems.add(new ApiError.Detail("method", "must be COUNTER or COMPLIMENTARY"));
        }
        if (request.method() == PaymentMethod.COMPLIMENTARY && (request.reason() == null || request.reason().trim().length() < MIN_REASON)) {
            problems.add(new ApiError.Detail("reason", "is required for a complimentary booking (at least " + MIN_REASON + " characters)"));
        }
        IssueRequest.Passenger passenger = request.passenger();
        if (passenger != null && passenger.mode() == IssueRequest.Passenger.Mode.WALK_IN && (passenger.name() == null || passenger.name().isBlank())) {
            problems.add(new ApiError.Detail("passenger.name", "is required for a walk-in passenger"));
        }
        if (passenger != null && passenger.mode() == IssueRequest.Passenger.Mode.ACCOUNT && (passenger.email() == null || passenger.email().isBlank())) {
            problems.add(new ApiError.Detail("passenger.email", "is required"));
        }
        if (passenger != null && passenger.mode() == IssueRequest.Passenger.Mode.WALK_IN && passenger.email() != null && !passenger.email().isBlank()
                && !passenger.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            problems.add(new ApiError.Detail("passenger.email", "is not a valid email address"));
        }
        if (!problems.isEmpty()) {
            throw new RequestValidationException(problems);
        }
    }

    private void issueInTransaction(String adminId, long bookingId, IssueRequest request) {
        Booking booking = locks.lock(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
        // A repeated request (a double click, a retry after a lost answer) finds the booking this admin already issued: one booking, never two.
        if (booking.status() == BookingStatus.CONFIRMED && booking.paymentMethod() == request.method() && adminId.equals(booking.issuedBy())) {
            return;
        }
        if (!booking.userId().equals(adminId)) {
            throw new BookingNotFoundException(bookingId); // only the admin's own hold can be issued
        }
        if (booking.status() == BookingStatus.EXPIRED || booking.status() == BookingStatus.ABANDONED) {
            throw new BookingConflictException("hold_expired", "The hold is over. Pick the seats again.");
        }
        if (booking.status() != BookingStatus.PENDING_PAYMENT) {
            throw new BookingConflictException("not_issuable", "This booking cannot be issued: it is already confirmed or cancelled.");
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        // An expired-but-unreleased hold still blocks the seats until the sweeper releases it, but it is not confirmed here ("payment wins" is for Stripe only).
        if (!booking.expiresAt().toInstant().isAfter(clock.instant())) {
            throw new BookingConflictException("hold_expired", "The hold ran out. Pick the seats again.");
        }
        if (booking.paymentStarted()) {
            throw new BookingConflictException("payment_started", "A card payment was already started for this hold. Release the seats and pick them again.");
        }

        KnownPassenger owner = null;
        String walkName = null;
        String walkPhone = null;
        String walkEmail = null;
        IssueRequest.Passenger passenger = request.passenger();
        if (passenger.mode() == IssueRequest.Passenger.Mode.ACCOUNT) {
            owner = lookup(passenger.email()).orElseThrow(PassengerNotFoundException::new);
        } else {
            walkName = passenger.name().trim();
            walkPhone = blankToNull(passenger.phone());
            walkEmail = blankToNull(passenger.email());
        }
        BigDecimal charged = request.method() == PaymentMethod.COUNTER ? booking.amount() : BigDecimal.ZERO.setScale(2);
        String note = request.method() == PaymentMethod.COMPLIMENTARY ? request.reason().trim() : blankToNull(request.note());
        bookings.markIssued(bookingId, request.method(), charged, adminId, now, note, owner, walkName, walkPhone, walkEmail);

        // The one confirmation of the card path: reservations lose their expiry, CONFIRMED, event after commit, ticket and email queued.
        ConfirmationService.Result result = confirmation.confirmLocked(booking);
        if (result != ConfirmationService.Result.CONFIRMED) {
            throw new BookingConflictException("not_issuable", "This booking cannot be issued.");
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("method", request.method().name());
        diff.put("amount", booking.amount());
        diff.put("charged", charged);
        diff.put("note", note);
        diff.put("mode", passenger.mode().name());
        diff.put("tripId", booking.tripId());
        audit.record(adminId, "booking", bookingId, "ISSUE", diff);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Marks a cancelled counter booking as refunded at the counter. Idempotent; any other state is a conflict. */
    public BookingView markRefundedAtCounter(String adminId, long bookingId) {
        tx.executeWithoutResult(status -> {
            Booking booking = locks.lock(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
            if (booking.refundStatus() == RefundStatus.REFUNDED_AT_COUNTER) {
                return;
            }
            if (booking.status() != BookingStatus.CANCELLED || booking.refundStatus() != RefundStatus.MANUAL_REFUND_DUE) {
                throw new BookingConflictException("not_refund_due", "Only a cancelled counter booking with a refund due at the counter can be marked as refunded.");
            }
            bookings.markCounterRefunded(bookingId, adminId, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            audit.record(adminId, "booking", bookingId, "COUNTER_REFUND", Map.of("amount", booking.amount(), "tripId", booking.tripId()));
        });
        return views.view(bookingId);
    }
}
