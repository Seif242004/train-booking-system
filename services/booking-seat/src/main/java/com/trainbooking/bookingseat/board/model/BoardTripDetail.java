package com.trainbooking.bookingseat.board.model;

import java.time.OffsetDateTime;
import java.util.List;

/** One trip with the state of every seat for the full journey (first stop to last stop) and the standing places per coach. */
public record BoardTripDetail(OffsetDateTime generatedAt, BoardTrip trip, List<BoardSeat> seats, List<BoardCoachStanding> standing) {
}
