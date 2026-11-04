package com.trainbooking.bookingseat.board.model;

/** Counts over the trips of the date range, before the state filter. {@code full} is a subset of {@code upcoming}. */
public record BoardSummary(int trips, int upcoming, int departed, int arrived, int full, int cancelled) {
}
