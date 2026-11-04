package com.trainbooking.bookingseat.board.model;

import java.time.OffsetDateTime;
import java.util.List;

public record BoardTripDetail(OffsetDateTime generatedAt, BoardTrip trip, List<BoardSeat> seats, List<BoardCoachStanding> standing) {
}
