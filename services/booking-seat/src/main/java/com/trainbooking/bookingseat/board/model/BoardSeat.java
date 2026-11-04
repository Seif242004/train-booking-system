package com.trainbooking.bookingseat.board.model;

/** FREE, HELD or TAKEN for the whole journey. The reference is set only for TAKEN seats, and nothing else about the passenger. */
// state is a string (FREE, HELD or TAKEN) for the board; holdExpiresAt tells an admin when a hold ends (null unless HELD).
public record BoardSeat(long seatId, long coachId, String state, String reference, java.time.OffsetDateTime holdExpiresAt) {
}
