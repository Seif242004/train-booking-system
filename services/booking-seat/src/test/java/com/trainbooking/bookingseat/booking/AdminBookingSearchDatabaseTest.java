package com.trainbooking.bookingseat.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.bookingseat.booking.model.AdminBookingView;
import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingNotFoundException;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.RefundStatus;
import com.trainbooking.bookingseat.booking.service.AdminBookingQueryService;
import com.trainbooking.bookingseat.booking.service.CancellationService;
import com.trainbooking.bookingseat.booking.service.CancellationService.Actor;
import com.trainbooking.bookingseat.booking.service.ConfirmationService;
import com.trainbooking.bookingseat.booking.service.PaymentResultService;
import com.trainbooking.bookingseat.common.RequestValidationException;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.Holds;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Support looks bookings up (Flow 7, FR-C6): by reference, user email, trip, status and refund status, and sees payment and refund status. */
class AdminBookingSearchDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9801;
    private static final long OTHER_TRIP = 9802;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "Alice.Example@example.com", "Alice");
    private static final AuthenticatedUser BOB = new AuthenticatedUser("bob-sub", "bob_smith@example.com", "Bob");

    @Autowired
    Holds holds;
    @Autowired
    ConfirmationService confirmations;
    @Autowired
    CancellationService cancellations;
    @Autowired
    PaymentResultService results;
    @Autowired
    AdminBookingQueryService queries;

    private BookingView alicesConfirmed;
    private BookingView bobsCancelled;
    private BookingView alicesHold;

    @BeforeEach
    void bookings() {
        insertTrip(TRIP);
        insertTrip(OTHER_TRIP);
        alicesConfirmed = holds.hold(ALICE, new HoldRequest(TRIP, 0, 3, List.of(2L), List.of()));
        confirmations.confirm(alicesConfirmed.id());
        bobsCancelled = holds.hold(BOB, new HoldRequest(TRIP, 0, 3, List.of(3L), List.of()));
        confirmations.confirm(bobsCancelled.id());
        cancellations.cancel(Actor.admin("admin-sub"), bobsCancelled.id(), "Called support");
        alicesHold = holds.hold(ALICE, new HoldRequest(OTHER_TRIP, 0, 3, List.of(2L), List.of()));
    }

    private List<Long> ids(List<AdminBookingView> views) {
        return views.stream().map(v -> v.booking().id()).toList();
    }

    @Test
    void noFilter_listsEverything_newestFirst() {
        assertThat(ids(queries.search(null, null, null, null, null))).containsExactly(alicesHold.id(), bobsCancelled.id(), alicesConfirmed.id());
    }

    @Test
    void byReference_ignoringCase() {
        assertThat(ids(queries.search(alicesConfirmed.reference().toLowerCase(), null, null, null, null))).containsExactly(alicesConfirmed.id());
        assertThat(queries.search("TB-NOPE00", null, null, null, null)).isEmpty();
    }

    @Test
    void byUserEmail_aCaseInsensitivePart() {
        assertThat(ids(queries.search(null, "alice.EXAMPLE", null, null, null))).containsExactly(alicesHold.id(), alicesConfirmed.id());
        assertThat(ids(queries.search(null, "@example.com", null, null, null))).hasSize(3);
    }

    @Test
    void theLikeCharactersInAnEmailSearch_areLiteral_notWildcards() {
        assertThat(queries.search(null, "%", null, null, null)).isEmpty();            // a wildcard would match everything
        assertThat(queries.search(null, "bob_smith", null, null, null)).hasSize(1);   // the underscore of the address
        assertThat(queries.search(null, "bobXsmith", null, null, null)).isEmpty();    // and not "any character"
        assertThat(queries.search(null, "x' OR '1'='1", null, null, null)).isEmpty(); // text, never SQL
    }

    @Test
    void byTrip_byStatus_andCombined() {
        assertThat(ids(queries.search(null, null, TRIP, null, null))).containsExactly(bobsCancelled.id(), alicesConfirmed.id());
        assertThat(ids(queries.search(null, null, null, "confirmed", null))).containsExactly(alicesConfirmed.id());
        assertThat(ids(queries.search(null, null, null, "CANCELLED", null))).containsExactly(bobsCancelled.id());
        assertThat(ids(queries.search(null, "alice", TRIP, "CONFIRMED", null))).containsExactly(alicesConfirmed.id());
        assertThat(queries.search(null, "bob", TRIP, "CONFIRMED", null)).isEmpty();
    }

    @Test
    void theFailedRefunds_forFollowUp() {
        results.refundStatus(bobsCancelled.id(), RefundStatus.REFUND_FAILED);

        assertThat(ids(queries.search(null, null, null, null, "REFUND_FAILED"))).containsExactly(bobsCancelled.id());
        assertThat(queries.search(null, null, null, null, "REFUNDED")).isEmpty();
    }

    @Test
    void anInvalidStatus_isRefusedWithTheField() {
        assertThatThrownBy(() -> queries.search(null, null, null, "WRONG", null)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> queries.search(null, null, null, null, "NOPE")).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void theDetails_showThePassenger_thePaymentAndRefundStatus_andWhoCancelledItWhy() {
        AdminBookingView cancelled = queries.details(bobsCancelled.id());
        AdminBookingView paid = queries.details(alicesConfirmed.id());
        AdminBookingView unpaid = queries.details(alicesHold.id());

        assertThat(cancelled.userEmail()).isEqualTo("bob_smith@example.com");
        assertThat(cancelled.userId()).isEqualTo("bob-sub");
        assertThat(cancelled.paymentStatus()).isEqualTo("PAID");                 // it was paid before it was cancelled
        assertThat(cancelled.booking().refundStatus()).isEqualTo(RefundStatus.REFUND_PENDING);
        assertThat(cancelled.cancelledBy()).isEqualTo("admin-sub");
        assertThat(cancelled.cancelledByRole()).isEqualTo("ADMIN");
        assertThat(cancelled.cancelReason()).isEqualTo("Called support");
        assertThat(cancelled.cancellable()).isFalse();
        assertThat(paid.paymentStatus()).isEqualTo("PAID");
        assertThat(paid.cancellable()).isTrue();
        assertThat(paid.cancelledBy()).isNull();
        assertThat(unpaid.paymentStatus()).isEqualTo("NOT_PAID");
        assertThat(unpaid.booking().items()).hasSize(1);
        assertThatThrownBy(() -> queries.details(987654)).isInstanceOf(BookingNotFoundException.class);
    }
}
