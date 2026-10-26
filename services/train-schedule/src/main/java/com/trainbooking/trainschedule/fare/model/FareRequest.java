package com.trainbooking.trainschedule.fare.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * One fare in the list sent to set the fares of a route. The currency is not sent: it is always EGP. Whether the segment
 * exists on this route is checked by the service, which knows the route.
 */
public record FareRequest(
        @NotNull(message = "is required")
        @Min(value = 0, message = "must be 0 or more")
        @Max(value = 48, message = "must be at most 48")
        Integer segmentIndex,

        @JsonProperty("class")
        @NotBlank(message = "is required")
        @Pattern(regexp = "^(FIRST|SECOND|THIRD)$", message = "must be FIRST, SECOND or THIRD")
        String coachClass,

        @NotNull(message = "is required")
        @DecimalMin(value = "0.01", message = "must be more than 0")
        @Digits(integer = 8, fraction = 2, message = "must have at most 8 digits and 2 decimals")
        BigDecimal amount) {
}
