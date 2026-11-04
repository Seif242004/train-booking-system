package com.trainbooking.bookingseat.booking.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.trainbooking.bookingseat.common.CoachClass;
import java.math.BigDecimal;

/** A line of a booking as shown to the passenger: a seat (with its coach) or standing places in a coach. */
// NON_NULL: a SEAT line has no coach quantity semantics and a STANDING line has no seat id; null fields are simply left out of the JSON.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingItemView(String type, Long seatId, String seatNumber, long coachId, String coachCode, CoachClass coachClass, int qty, BigDecimal price) {
}
