package com.trainbooking.bookingseat.board.model;

import java.time.OffsetDateTime;
import java.util.List;

public record BoardResponse(OffsetDateTime generatedAt, OffsetDateTime from, OffsetDateTime to, BoardSummary summary, List<BoardTrip> trips) {
}
