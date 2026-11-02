package com.trainbooking.trainschedule.trip.service;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trip search for passengers (FR-S1, FR-S2): trips from one station to another on a date. */
@Service
public class TripSearchService {

    private final TripRepository trips;
    private final StationService stations;
    private final FareRepository fares;
    private final ZoneId zone;
    private final Clock clock;

    public TripSearchService(TripRepository trips, StationService stations, FareRepository fares, ZoneId zone, Clock clock) {
        this.trips = trips;
        this.stations = stations;
        this.fares = fares;
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * Finds the trips that leave {@code fromCode} on {@code date} (the departure date at the origin, in the service time
     * zone) and later arrive at {@code toCode}. Cancelled trips are never returned, and a trip is returned only where the
     * origin comes before the destination on its route. Results are ordered by departure time.
     * <p>When {@code date} is today (in the service zone), trips that have already left the origin are not returned. When
     * nothing is found, the response carries the next date that has a trip for this origin and destination, if there is one.
     * The seats and standing places left are added by the Gateway, from Booking & Seat.
     */
    @Transactional(readOnly = true)
    public TripSearchResponse search(String fromCode, String toCode, LocalDate date) {
        Station from = station("from", fromCode);
        Station to = station("to", toCode);
        if (from.id() == to.id()) {
            throw new RequestValidationException(List.of(new ApiError.Detail("to", "must be a different station than from")));
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        var dayStart = date.atStartOfDay(zone).toOffsetDateTime();
        var dayEnd = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        // Today, a trip that has left the origin stop is gone, so the window starts at the current instant.
        var windowStart = date.equals(today) && now.isAfter(dayStart) ? now : dayStart;
        List<TripRepository.SearchRow> rows = trips.search(from.id(), to.id(), windowStart, dayEnd);
        if (rows.isEmpty()) {
            // Never suggest a day in the past: look from the end of the searched day, or from now if that is already behind us.
            var notBefore = dayEnd.isAfter(now) ? dayEnd : now;
            LocalDate next = trips.findNextDeparture(from.id(), to.id(), notBefore).map(LocalDateTime::toLocalDate).orElse(null);
            return new TripSearchResponse(List.of(), next);
        }

        // One query for the stops of every matching trip.
        Map<Long, List<TripStop>> stopsByTrip = trips.findStops(rows.stream().map(TripRepository.SearchRow::tripId).toList());

        Map<Long, List<Fare>> faresByRoute = new HashMap<>();
        List<TripSearchResult> results = new ArrayList<>();
        for (TripRepository.SearchRow row : rows) {
            List<Fare> routeFares = faresByRoute.computeIfAbsent(row.routeId(), fares::findByRoute);
            results.add(new TripSearchResult(row.tripId(), row.trainNumber(), row.trainName(), row.trainType(), row.routeName(),
                    from, to, row.fromStopIndex(), row.toStopIndex(), row.departure(), row.arrival(),
                    stopsByTrip.getOrDefault(row.tripId(), List.of()),
                    journeyFares(routeFares, row.fromStopIndex(), row.toStopIndex())));
        }
        return new TripSearchResponse(results, null);
    }

    /**
     * The fare of a journey is the sum of the fares of the segments it uses (CLAUDE.md, "Fares"): segments
     * {@code fromStop} to {@code toStop - 1}. A class is listed only if every one of those segments has a fare for it.
     */
    static List<JourneyFare> journeyFares(List<Fare> routeFares, int fromStop, int toStop) {
        List<JourneyFare> result = new ArrayList<>();
        for (CoachClass coachClass : CoachClass.values()) {
            BigDecimal total = BigDecimal.ZERO;
            boolean complete = true;
            for (int segment = fromStop; segment < toStop; segment++) {
                BigDecimal amount = amountOf(routeFares, segment, coachClass);
                if (amount == null) {
                    complete = false;
                    break;
                }
                total = total.add(amount);
            }
            if (complete) {
                result.add(new JourneyFare(coachClass, total.setScale(2), Fare.EGP));
            }
        }
        return result;
    }

    private static BigDecimal amountOf(List<Fare> routeFares, int segment, CoachClass coachClass) {
        for (Fare fare : routeFares) {
            if (fare.segmentIndex() == segment && fare.coachClass() == coachClass) {
                return fare.amount();
            }
        }
        return null;
    }

    private Station station(String field, String code) {
        return stations.findByCode(code)
                .orElseThrow(() -> new RequestValidationException(List.of(new ApiError.Detail(field, "unknown station code " + code))));
    }
}
