package com.trainbooking.bookingseat.booking.model;

import java.util.List;

/**
 * Some of the seats or standing places asked for are not available on every segment of the journey (SRS FR-H2). Nothing was
 * held. Answered as 409 {@code seats_unavailable}, listing what was lost, so that the page can refresh the seat map.
 */
public class HoldConflictException extends RuntimeException {

    /** @param type SEAT or STANDING; @param id the seat id or the coach id; @param label for people, for example "seat 1A of coach A1" */
    public record Lost(String type, long id, String label) {
    }

    private final transient List<Lost> lost;

    public HoldConflictException(List<Lost> lost) {
        super("Some of the places you chose are no longer available.");
        this.lost = List.copyOf(lost);
    }

    public List<Lost> lost() {
        return lost;
    }
}
