package com.trainbooking.trainschedule.fare.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import java.math.BigDecimal;

/**
 * The price of one segment of a route for one coach class (SRS section 8: route_id, segment_index, coach_class, amount,
 * currency). The route is the one in the URL. Segment k is the leg from stop k to stop k+1, and the fare of a journey
 * is the sum of the fares of its segments. {@code class} is a Java keyword, so the component is {@code coachClass}; the
 * JSON name stays {@code class}, as for coaches and seats.
 */
public record Fare(int segmentIndex, @JsonProperty("class") CoachClass coachClass, BigDecimal amount, String currency) {

    /** All fares are in Egyptian pounds (CLAUDE.md, "Fares"). */
    public static final String EGP = "EGP";
}
