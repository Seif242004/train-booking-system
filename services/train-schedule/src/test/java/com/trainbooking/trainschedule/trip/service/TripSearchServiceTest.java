package com.trainbooking.trainschedule.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.fare.model.Fare;
import com.trainbooking.trainschedule.fare.repository.FareRepository;
import com.trainbooking.trainschedule.station.model.Station;
import com.trainbooking.trainschedule.station.service.StationService;
import com.trainbooking.trainschedule.trip.model.JourneyFare;
import com.trainbooking.trainschedule.trip.model.TripSearchResponse;
import com.trainbooking.trainschedule.trip.model.TripSearchResult;
import com.trainbooking.trainschedule.trip.model.TripStop;
import com.trainbooking.trainschedule.trip.repository.TripRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TripSearchServiceTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final Station CAI = new Station(1, "CAI", "Cairo", "Cairo");
    private static final Station GIZ = new Station(2, "GIZ", "Giza", "Giza");
    private static final Station ALX = new Station(3, "ALX", "Alexandria", "Alexandria");

    private final TripRepository trips = mock(TripRepository.class);
    private final StationService stations = mock(StationService.class);
    private final FareRepository fares = mock(FareRepository.class);
    // "Now" is Monday 26 October 2026, 10:00 in Cairo.
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final OffsetDateTime NOW = LocalDateTime.of(2026, 10, 26, 10, 0).atZone(CAIRO).toOffsetDateTime();
    private final TripSearchService service =
            new TripSearchService(trips, stations, fares, CAIRO, Clock.fixed(NOW.toInstant(), CAIRO));

    private static Fare fare(int segment, CoachClass coachClass, String amount) {
        return new Fare(segment, coachClass, new BigDecimal(amount), "EGP");
    }

    private static TripRepository.SearchRow row(long tripId, int fromIndex, int toIndex) {
        return new TripRepository.SearchRow(tripId, "T100", "Cairo-Alexandria Express", "Express", 4, "Cairo-Alexandria",
                fromIndex, toIndex, LocalDateTime.of(2026, 11, 2, 8, 0), LocalDateTime.of(2026, 11, 2, 10, 45));
    }

    private static List<TripStop> threeStops() {
        return List.of(
                new TripStop(0, 1, "CAI", "Cairo", null, LocalDateTime.of(2026, 11, 2, 8, 0)),
                new TripStop(1, 2, "GIZ", "Giza", LocalDateTime.of(2026, 11, 2, 8, 40), LocalDateTime.of(2026, 11, 2, 8, 45)),
                new TripStop(2, 3, "ALX", "Alexandria", LocalDateTime.of(2026, 11, 2, 10, 45), null));
    }

    @Test
    void search_asksForTheLocalDayInTheServiceZone_andFindsStationsByCodeInAnyCase() {
        given(stations.findByCode("cai")).willReturn(Optional.of(CAI));
        given(stations.findByCode("alx")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of());

        service.search("cai", "alx", DAY);

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(trips).search(eq(1L), eq(3L), start.capture(), end.capture());
        assertThat(start.getValue().toLocalDateTime()).isEqualTo(DAY.atStartOfDay());
        assertThat(end.getValue().toLocalDateTime()).isEqualTo(DAY.plusDays(1).atStartOfDay());
        assertThat(start.getValue().getOffset()).isEqualTo(CAIRO.getRules().getOffset(DAY.atStartOfDay()));
    }

    @Test
    void search_today_hidesTripsThatHaveAlreadyLeft_byStartingTheWindowAtNow() {
        given(stations.findByCode("CAI")).willReturn(Optional.of(CAI));
        given(stations.findByCode("ALX")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of(row(21, 0, 2)));
        given(fares.findByRoute(4)).willReturn(List.of());

        service.search("CAI", "ALX", TODAY);

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(trips).search(eq(1L), eq(3L), start.capture(), end.capture());
        assertThat(start.getValue().toInstant()).isEqualTo(NOW.toInstant()); // not midnight: 08:00 trips are gone at 10:00
        assertThat(end.getValue().toLocalDateTime()).isEqualTo(TODAY.plusDays(1).atStartOfDay());
    }

    @Test
    void search_aFutureDate_isNotCutAtNow_andAPastDateKeepsItsOwnDay() {
        given(stations.findByCode("CAI")).willReturn(Optional.of(CAI));
        given(stations.findByCode("ALX")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of());

        service.search("CAI", "ALX", TODAY.plusDays(1));
        service.search("CAI", "ALX", TODAY.minusDays(1));

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(trips, times(2)).search(eq(1L), eq(3L), start.capture(), any());
        assertThat(start.getAllValues()).extracting(OffsetDateTime::toLocalDateTime)
                .containsExactly(TODAY.plusDays(1).atStartOfDay(), TODAY.minusDays(1).atStartOfDay());
    }

    @Test
    void search_withNoTrips_carriesTheDateOfTheNextTripFromTheEndOfTheSearchedDay() {
        given(stations.findByCode("CAI")).willReturn(Optional.of(CAI));
        given(stations.findByCode("ALX")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of());
        given(trips.findNextDeparture(eq(1L), eq(3L), any())).willReturn(Optional.of(LocalDateTime.of(2026, 11, 9, 23, 30)));

        TripSearchResponse response = service.search("CAI", "ALX", DAY);

        assertThat(response.trips()).isEmpty();
        assertThat(response.nextAvailableDate()).isEqualTo(LocalDate.of(2026, 11, 9));
        ArgumentCaptor<OffsetDateTime> notBefore = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(trips).findNextDeparture(eq(1L), eq(3L), notBefore.capture());
        assertThat(notBefore.getValue().toLocalDateTime()).isEqualTo(DAY.plusDays(1).atStartOfDay()); // the day after the searched one
    }

    @Test
    void search_withNoTripsAndNoLaterTrip_hasNoNextDate() {
        given(stations.findByCode("CAI")).willReturn(Optional.of(CAI));
        given(stations.findByCode("ALX")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of());
        given(trips.findNextDeparture(anyLong(), anyLong(), any())).willReturn(Optional.empty());

        assertThat(service.search("CAI", "ALX", DAY).nextAvailableDate()).isNull();
    }

    @Test
    void search_forADayInThePast_neverSuggestsADayBeforeNow() {
        given(stations.findByCode("CAI")).willReturn(Optional.of(CAI));
        given(stations.findByCode("ALX")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of());
        given(trips.findNextDeparture(anyLong(), anyLong(), any())).willReturn(Optional.empty());

        service.search("CAI", "ALX", TODAY.minusDays(5));

        ArgumentCaptor<OffsetDateTime> notBefore = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(trips).findNextDeparture(anyLong(), anyLong(), notBefore.capture());
        assertThat(notBefore.getValue().toInstant()).isEqualTo(NOW.toInstant());
    }

    @Test
    void search_withTripsFound_doesNotLookForTheNextDate() {
        given(stations.findByCode("CAI")).willReturn(Optional.of(CAI));
        given(stations.findByCode("ALX")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of(row(21, 0, 2)));
        given(fares.findByRoute(4)).willReturn(List.of());

        assertThat(service.search("CAI", "ALX", DAY).nextAvailableDate()).isNull();
        verify(trips, never()).findNextDeparture(anyLong(), anyLong(), any());
    }

    @Test
    void search_anUnknownStationCode_isRejected_namingTheParameter() {
        given(stations.findByCode("CAI")).willReturn(Optional.of(CAI));
        given(stations.findByCode("XXX")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.search("CAI", "XXX", DAY))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).details()).extracting(ApiError.Detail::field).containsExactly("to"));
        verify(trips, never()).search(anyLong(), anyLong(), any(), any());
    }

    @Test
    void search_theSameStationTwice_isRejected() {
        given(stations.findByCode("CAI")).willReturn(Optional.of(CAI));

        assertThatThrownBy(() -> service.search("CAI", "CAI", DAY))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).details()).extracting(ApiError.Detail::field).containsExactly("to"));
    }

    @Test
    void search_returnsTheTripsWithTheJourneyFarePerClass_theSumOfItsSegments() {
        given(stations.findByCode("CAI")).willReturn(Optional.of(CAI));
        given(stations.findByCode("ALX")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of(row(21, 0, 2)));
        given(fares.findByRoute(4)).willReturn(List.of(
                fare(0, CoachClass.FIRST, "80"), fare(0, CoachClass.SECOND, "50"), fare(0, CoachClass.THIRD, "30"),
                fare(1, CoachClass.FIRST, "150"), fare(1, CoachClass.SECOND, "100"), fare(1, CoachClass.THIRD, "60")));

        List<TripSearchResult> results = service.search("CAI", "ALX", DAY).trips();

        assertThat(results).hasSize(1);
        TripSearchResult result = results.get(0);
        assertThat(result.tripId()).isEqualTo(21);
        assertThat(result.from()).isEqualTo(CAI);
        assertThat(result.to()).isEqualTo(ALX);
        assertThat(result.fares()).extracting(JourneyFare::coachClass, f -> f.amount().toPlainString(), JourneyFare::currency)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(CoachClass.FIRST, "230.00", "EGP"),
                        org.assertj.core.groups.Tuple.tuple(CoachClass.SECOND, "150.00", "EGP"),
                        org.assertj.core.groups.Tuple.tuple(CoachClass.THIRD, "90.00", "EGP"));
    }

    @Test
    void search_eachResultCarriesAllTheStopsOfItsTrip_inOrder_whateverTheJourney() {
        given(stations.findByCode("GIZ")).willReturn(Optional.of(GIZ));
        given(stations.findByCode("ALX")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of(row(21, 1, 2), row(22, 1, 2)));
        given(trips.findStops(List.of(21L, 22L))).willReturn(Map.of(21L, threeStops()));
        given(fares.findByRoute(4)).willReturn(List.of());

        List<TripSearchResult> results = service.search("GIZ", "ALX", DAY).trips();

        // The journey is stops 1 to 2, but the stop before it (Cairo) is still listed so the client can dim it.
        assertThat(results.get(0).stops()).extracting(TripStop::stopIndex, TripStop::stationCode)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, "CAI"), org.assertj.core.groups.Tuple.tuple(1, "GIZ"),
                        org.assertj.core.groups.Tuple.tuple(2, "ALX"));
        assertThat(results.get(0).stops().get(1).departureTime()).isEqualTo(LocalDateTime.of(2026, 11, 2, 8, 45));
        assertThat(results.get(0).fromStopIndex()).isEqualTo(1);
        assertThat(results.get(0).toStopIndex()).isEqualTo(2);
        assertThat(results.get(1).stops()).isEmpty(); // nothing loaded for trip 22: an empty list, never null
        verify(trips, times(1)).findStops(any()); // one query for all the trips, not one per trip
    }

    @Test
    void search_aJourneyOnOneSegmentOnly_usesOnlyThatSegmentsFare() {
        given(stations.findByCode("GIZ")).willReturn(Optional.of(GIZ));
        given(stations.findByCode("ALX")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of(row(21, 1, 2)));
        given(fares.findByRoute(4)).willReturn(List.of(fare(0, CoachClass.FIRST, "80"), fare(1, CoachClass.FIRST, "150")));

        List<TripSearchResult> results = service.search("GIZ", "ALX", DAY).trips();

        assertThat(results.get(0).fares()).extracting(f -> f.amount().toPlainString()).containsExactly("150.00");
    }

    @Test
    void search_aClassWithAMissingSegmentFare_isLeftOutRatherThanPricedWrongly() {
        List<Fare> routeFares = List.of(fare(0, CoachClass.FIRST, "80"), fare(1, CoachClass.FIRST, "150"), fare(0, CoachClass.SECOND, "50"));

        List<JourneyFare> fares = TripSearchService.journeyFares(routeFares, 0, 2);

        assertThat(fares).extracting(JourneyFare::coachClass).containsExactly(CoachClass.FIRST); // SECOND lacks segment 1
    }

    @Test
    void search_loadsTheFaresOfEachRouteOnlyOnce() {
        given(stations.findByCode("CAI")).willReturn(Optional.of(CAI));
        given(stations.findByCode("ALX")).willReturn(Optional.of(ALX));
        given(trips.search(anyLong(), anyLong(), any(), any())).willReturn(List.of(row(21, 0, 2), row(22, 0, 2), row(23, 0, 2)));
        given(fares.findByRoute(4)).willReturn(List.of());

        service.search("CAI", "ALX", DAY);

        verify(fares, times(1)).findByRoute(4);
    }
}
