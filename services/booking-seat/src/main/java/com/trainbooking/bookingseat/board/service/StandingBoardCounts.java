package com.trainbooking.bookingseat.board.service;

import com.trainbooking.bookingseat.board.model.SeatCounts;
import com.trainbooking.bookingseat.board.repository.BoardRepository.StandingBookingRow;
import com.trainbooking.bookingseat.board.repository.BoardRepository.StandingRow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The board's three counts for the standing places of ONE coach, by the same full-journey rule as the seats:
 * <ul>
 *   <li>free = the minimum of (capacity - used) over every segment of the trip (a missing counter row counts as no capacity);</li>
 *   <li>confirmed = the most places that confirmed bookings use on any one segment;</li>
 *   <li>held = what is used beyond that (running holds), so that confirmed + held + free = capacity.</li>
 * </ul>
 */
public final class StandingBoardCounts {

    private StandingBoardCounts() {
    }

    public static SeatCounts of(int stops, List<StandingRow> usage, List<StandingBookingRow> bookings) {
        int segments = Math.max(stops - 1, 0);
        Map<Integer, StandingRow> bySegment = new HashMap<>();
        int capacity = 0;
        for (StandingRow row : usage) {
            bySegment.put(row.segment(), row);
            capacity = Math.max(capacity, row.capacity());
        }
        int free = capacity;
        for (int segment = 0; segment < segments; segment++) {
            StandingRow row = bySegment.get(segment);
            free = Math.min(free, row == null ? 0 : row.capacity() - row.used());
        }
        free = Math.max(free, 0);

        int[] confirmedPerSegment = new int[segments];
        for (StandingBookingRow booking : bookings) {
            if (!"CONFIRMED".equals(booking.status())) {
                continue;
            }
            for (int segment = booking.fromStop(); segment < booking.toStop() && segment < segments; segment++) {
                confirmedPerSegment[segment] += booking.qty();
            }
        }
        int confirmed = 0;
        for (int value : confirmedPerSegment) {
            confirmed = Math.max(confirmed, value);
        }
        confirmed = Math.min(confirmed, capacity - free);
        return new SeatCounts(capacity, confirmed, capacity - free - confirmed, free);
    }
}
