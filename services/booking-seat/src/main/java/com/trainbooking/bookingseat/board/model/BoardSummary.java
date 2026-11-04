package com.trainbooking.bookingseat.board.model;

/** Counts over the trips of the date range, before the state filter. {@code full} is a subset of {@code upcoming}. */
// The summary always counts the whole range, so the cards on the page do not change when an admin filters the table.
public record BoardSummary(int trips, int upcoming, int departed, int arrived, int full, int cancelled) {
}
