package com.trainbooking.trainschedule.train.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Validation annotations on the components (checked because the controller uses @Valid): @NotBlank = present and not only spaces,
// @Size(max) = length limit, @Pattern = regular expression (status must be exactly ACTIVE or INACTIVE; the service turns it into the enum).
// "type" is free text (Express, Sleeper, ...). The number is stored upper-case and is unique.
/** Body of create and update (FR-A1, FR-A3). */
public record TrainRequest(
        @NotBlank(message = "is required")
        @Size(max = 20, message = "must be at most 20 characters")
        String number,

        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String name,

        @NotBlank(message = "is required")
        @Size(max = 50, message = "must be at most 50 characters")
        String type,

        @NotBlank(message = "is required")
        @Pattern(regexp = "^(ACTIVE|INACTIVE)$", message = "must be ACTIVE or INACTIVE")
        String status) {
}
