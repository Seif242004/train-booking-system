package com.trainbooking.trainschedule.seat.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * One seat in a create (a list of these) or an update. There is deliberately no class field: the server takes the
 * class from the coach, so a client can neither set nor change it.
 */
public record SeatRequest(
        // Rules (checked by Bean Validation): @NotBlank = present and not only spaces; @Pattern = regular expression; @NotNull = number
        // present;
        // @Min/@Max = range. The seat number is stored upper-case; row is the line in the coach (1 to 99), column the letter(s) A, B, C ...
        // Because the bulk create takes a list, this class is validated per item by ListValidation (so errors say "[2].row"), not by
        // @Valid.
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z0-9]{1,10}$", message = "must be 1 to 10 letters or digits")
        String seatNumber,

        @NotNull(message = "is required")
        @Min(value = 1, message = "must be 1 or more")
        @Max(value = 99, message = "must be at most 99")
        Integer row,

        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z]{1,3}$", message = "must be 1 to 3 letters")
        String column) {
}
