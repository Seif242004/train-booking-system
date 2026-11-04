package com.trainbooking.bookingseat.board.model;

import java.time.OffsetDateTime;
import java.util.List;

/** The answer of the board list: when it was made, the date range used, the summary of the whole range, and the (filtered) trip rows. */
public record BoardResponse(OffsetDateTime generatedAt, OffsetDateTime from, OffsetDateTime to, BoardSummary summary, List<BoardTrip> trips) {
}
