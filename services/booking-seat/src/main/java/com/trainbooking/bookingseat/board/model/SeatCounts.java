package com.trainbooking.bookingseat.board.model;

/** capacity = confirmed + held + free, always. */
public record SeatCounts(int capacity, int confirmed, int held, int free) {

    public static final SeatCounts NONE = new SeatCounts(0, 0, 0, 0);

    public SeatCounts plus(SeatCounts other) {
        return new SeatCounts(capacity + other.capacity, confirmed + other.confirmed, held + other.held, free + other.free);
    }
}
