package com.trainbooking.trainschedule.trip.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

/**
 * The answer to a trip search: the trips found and, only when there are none, the next date that has a trip for the same
 * origin and destination. {@code nextAvailableDate} is left out of the JSON when no later trip exists.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripSearchResponse(List<TripSearchResult> trips, LocalDate nextAvailableDate) {
}
