package com.trainbooking.trainschedule.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.trainbooking.trainschedule.trip.model.BookingConflictException;
import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripStatus;
import com.trainbooking.trainschedule.trip.repository.TripRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * When a train, coach, seat, fare, route or station is edited, the snapshots of the future trips it touches are pushed
 * again just before the commit, once per trip, and a refusal of Booking and Seat reaches the transaction.
 */
@SuppressWarnings("unchecked")
class TripSnapshotRefresherTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private final TripRepository trips = mock(TripRepository.class);
    private final TripSnapshotPublisher publisher = mock(TripSnapshotPublisher.class);
    private final ObjectProvider<TripSnapshotPublisher> provider = mock(ObjectProvider.class);
    private final TripSnapshotRefresher refresher = new TripSnapshotRefresher(trips, provider, NOW);

    private static Trip trip(long id) {
        return new Trip(id, 7, "T100", "Express", 4, "Route", LocalDate.of(2026, 11, 2), TripStatus.ACTIVE, List.of());
    }

    @BeforeEach
    void inATransaction() {
        given(provider.getObject()).willReturn(publisher);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void endTheTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationUtils.triggerAfterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);
            TransactionSynchronizationManager.clear();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(TripSnapshotRefresher.class);
    }

    private void commit() {
        TransactionSynchronizationUtils.triggerBeforeCommit(false);
    }

    @Test
    void nothingIsPushed_untilJustBeforeTheCommit() {
        refresher.trainChanged(7, "admin-1");

        verifyNoInteractions(trips, publisher);
    }

    @Test
    void aTrainEdit_pushesEveryFutureActiveTripOfTheTrain_withTheAdmin() {
        OffsetDateTime now = OffsetDateTime.now(NOW);
        given(trips.findFutureActiveIdsByTrain(7, now)).willReturn(List.of(11L, 12L));
        given(trips.findById(11)).willReturn(Optional.of(trip(11)));
        given(trips.findById(12)).willReturn(Optional.of(trip(12)));

        refresher.trainChanged(7, "admin-1");
        commit();

        verify(publisher).publish(trip(11), "admin-1");
        verify(publisher).publish(trip(12), "admin-1");
    }

    @Test
    void aCoachAndItsSeatsEditedTogether_pushTheTripOnlyOnce() {
        given(trips.findFutureActiveIdsByTrain(any(Long.class), any())).willReturn(List.of(11L));
        given(trips.findById(11)).willReturn(Optional.of(trip(11)));

        refresher.trainChanged(7, "admin-1"); // the coach
        refresher.trainChanged(7, "admin-1"); // its seats
        refresher.routeChanged(4, "admin-1"); // and a fare, which touches the same trip
        given(trips.findFutureActiveIdsByRoute(any(Long.class), any())).willReturn(List.of(11L));
        commit();

        verify(publisher, times(1)).publish(any(), any());
    }

    @Test
    void aFareEditOrAStationRename_pushesTheTripsOfTheRouteOrTheStation() {
        OffsetDateTime now = OffsetDateTime.now(NOW);
        given(trips.findFutureActiveIdsByRoute(4, now)).willReturn(List.of(11L));
        given(trips.findFutureActiveIdsByStation(9, now)).willReturn(List.of(12L));
        given(trips.findById(11)).willReturn(Optional.of(trip(11)));
        given(trips.findById(12)).willReturn(Optional.of(trip(12)));

        refresher.routeChanged(4, "admin-1");
        refresher.stationChanged(9, "admin-2");
        commit();

        verify(publisher).publish(trip(11), "admin-1");
        verify(publisher).publish(trip(12), "admin-2");
    }

    @Test
    void whenThereAreNoFutureTrips_nothingIsPushed() {
        given(trips.findFutureActiveIdsByTrain(any(Long.class), any())).willReturn(List.of());

        refresher.trainChanged(7, "admin-1");
        commit();

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void aRefusalOfBookingAndSeat_reachesTheCommit_soTheEditIsRolledBack() {
        given(trips.findFutureActiveIdsByRoute(eq(4L), any())).willReturn(List.of(11L));
        given(trips.findById(11)).willReturn(Optional.of(trip(11)));
        org.mockito.Mockito.doThrow(new BookingConflictException("Trip 11 has holds or bookings.", List.of())).when(publisher).publish(any(), any());

        refresher.routeChanged(4, "admin-1");

        assertThatThrownBy(this::commit).isInstanceOf(BookingConflictException.class);
    }

    @Test
    void outsideATransaction_thePushHappensAtOnce() {
        TransactionSynchronizationManager.clearSynchronization();
        given(trips.findFutureActiveIdsByTrain(any(Long.class), any())).willReturn(List.of(11L));
        given(trips.findById(11)).willReturn(Optional.of(trip(11)));

        refresher.trainChanged(7, "admin-1");

        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        verify(publisher).publish(trip(11), "admin-1");
    }
}
