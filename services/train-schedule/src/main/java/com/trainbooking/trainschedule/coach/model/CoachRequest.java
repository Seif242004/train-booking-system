package com.trainbooking.trainschedule.coach.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Body of add and update coach (FR-A5, FR-A7). The JSON field {@code class} arrives as {@code coachClass}. */
public record CoachRequest(
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z0-9]{1,10}$", message = "must be 1 to 10 letters or digits")
        String code,

        @JsonProperty("class")
        @NotBlank(message = "is required")
        @Pattern(regexp = "^(FIRST|SECOND|THIRD)$", message = "must be FIRST, SECOND or THIRD")
        String coachClass,

        @NotNull(message = "is required")
        @Min(value = 1, message = "must be 1 or more")
        @Max(value = 99, message = "must be at most 99")
        Integer position,

        @NotNull(message = "is required")
        @Min(value = 0, message = "must be 0 or more")
        @Max(value = 1000, message = "must be at most 1000")
        Integer standingCapacity) {
}
