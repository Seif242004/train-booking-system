package com.trainbooking.bookingseat.booking.model;

/**
 * A line of "My bookings": the booking, and what the passenger may do with it. {@code cancellable} is true for a confirmed booking whose train
 * has not yet left the boarding stop (the server still checks when the cancellation arrives).
 */
public record MyBooking(BookingView booking, boolean cancellable) {
}
