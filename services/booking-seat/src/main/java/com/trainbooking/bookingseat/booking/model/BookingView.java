package com.trainbooking.bookingseat.booking.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A booking as returned by the API. {@code serverTime} lets the browser correct its countdown for a clock that is off: the
 * countdown is driven by the expiry time of the server (SRS FR-H4).
 */
public record BookingView(
        long id,
        String reference,
        BookingStatus status,
        RefundStatus refundStatus,
        long tripId,
        int fromStop,
        int toStop,
        BigDecimal amount,
        String currency,
        OffsetDateTime expiresAt,
        OffsetDateTime serverTime,
        OffsetDateTime createdAt,
        TripInfo trip,
        List<BookingItemView> items,
        PaymentMethod paymentMethod) {

    /** A card booking (the original shape of the view). */
    public BookingView(long id, String reference, BookingStatus status, RefundStatus refundStatus, long tripId, int fromStop, int toStop, BigDecimal amount,
                       String currency, OffsetDateTime expiresAt, OffsetDateTime serverTime, OffsetDateTime createdAt, TripInfo trip, List<BookingItemView> items) {
        this(id, reference, status, refundStatus, tripId, fromStop, toStop, amount, currency, expiresAt, serverTime, createdAt, trip, items, PaymentMethod.CARD);
    }


    /** The trip and the stops of the journey, for the payment page and "My bookings". */
    public record TripInfo(String trainNumber, String trainName, String routeName, LocalDate departureDate, StopInfo from, StopInfo to) {
    }

    public record StopInfo(int stopIndex, String stationCode, String stationName, OffsetDateTime arrivalTime, OffsetDateTime departureTime) {
    }
}
