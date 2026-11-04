package com.trainbooking.bookingseat.board.model;

// The standing places of one coach in the detail view, counted by StandingBoardCounts.
public record BoardCoachStanding(long coachId, SeatCounts counts) {
}
