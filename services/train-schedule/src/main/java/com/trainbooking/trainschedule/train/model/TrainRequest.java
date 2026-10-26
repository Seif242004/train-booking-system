package com.trainbooking.trainschedule.train.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
