package com.trainbooking.trainschedule.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import java.math.BigDecimal;

/** The price of a whole journey for one class: the sum of the fares of the segments it uses, in EGP. */
// Built in TripSearchService by adding the fares of segments fromStopIndex..toStopIndex-1 for each class. @JsonProperty("class") renames
// the field in JSON.
public record JourneyFare(@JsonProperty("class") CoachClass coachClass, BigDecimal amount, String currency) {
}
