package com.trainbooking.trainschedule.coach.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A coach as stored and returned by the API (SRS section 8: id, train_id, code, class, position, standing_capacity).
 * {@code class} is a Java keyword, so the component is {@code coachClass}; the JSON name stays {@code class}.
 */
public record Coach(
        long id,
        long trainId,
        String code,
        @JsonProperty("class") CoachClass coachClass,
        int position,
        int standingCapacity) {
}
