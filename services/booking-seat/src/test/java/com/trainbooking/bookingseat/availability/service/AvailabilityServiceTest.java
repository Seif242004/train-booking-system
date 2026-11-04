package com.trainbooking.bookingseat.availability.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.trainbooking.bookingseat.availability.model.TripAvailability;
import com.trainbooking.bookingseat.availability.model.TripHeader;
import com.trainbooking.bookingseat.availability.repository.AvailabilityRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Unit test with a mocked repository: the service must read the trip version BEFORE the data, so a live-event version number is never
 * newer than the seats the client got (the client drops events up to that version). No database.
 */
class AvailabilityServiceTest {

    private final AvailabilityRepository repository = mock(AvailabilityRepository.class);
    private final AvailabilityService service = new AvailabilityService(repository);

    // @Test: JUnit 5: this method is one test. It passes when it ends without an exception; its name states the rule it proves.
    @Test
    void theVersionIsReadBeforeTheData_soTheDataIsNeverOlderThanTheVersion() {
        given(repository.findTrip(5)).willReturn(Optional.of(new TripHeader(5, "OPEN", 3)));
        given(repository.findVersion(5)).willReturn(11L);
        given(repository.findSeats(5)).willReturn(List.of());
        given(repository.findStanding(5)).willReturn(List.of());
        given(repository.findFares(5)).willReturn(List.of());
        given(repository.findReservations(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.any())).willReturn(List.of());

        TripAvailability result = service.availability(5, 0, 2);

        assertThat(result.version()).isEqualTo(11);
        InOrder order = inOrder(repository);
        order.verify(repository).findVersion(5);
        order.verify(repository).findSeats(5);
        order.verify(repository).findStanding(5);
    }
}
