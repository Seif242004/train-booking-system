package com.trainbooking.trainschedule.station.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of create and update. Validated at the service boundary, before any business logic runs (the controller marks it @Valid).
 * Bean Validation annotations on record components: @NotBlank = not null and not only spaces; @Pattern = must match the regular
 * expression; @Size(max) = text length limit. The "message" text is what the client sees in {@code error.details}.
 */
public record StationRequest(
        // Letters and digits only, 2 to 10 characters. The service stores it upper-case.
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z0-9]{2,10}$", message = "must be 2 to 10 letters or digits")
        String code,

        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String name,

        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String city) {
}
