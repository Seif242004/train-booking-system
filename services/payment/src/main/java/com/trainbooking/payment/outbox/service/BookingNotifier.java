package com.trainbooking.payment.outbox.service;

/**
 * Tells Booking and Seat about payments and refunds. An interface so that the delivery logic (retry, order, what a refusal means)
 * can be tested without a Booking service.
 */
public interface BookingNotifier {

    /** What Booking answered to a payment that succeeded. */
    enum PaymentAnswer {
        /** The booking is confirmed (now, or it was before). */
        CONFIRMED,
        /** The booking cannot take this payment (its hold is gone, or the amount differs): the money must be refunded. */
        REFUND_REQUIRED
    }

    /**
     * @throws BookingRejectedException     Booking will never accept this message (for example it does not know the booking)
     * @throws BookingUnavailableException  Booking could not be reached or failed: try again later
     */
    PaymentAnswer paymentResult(long bookingId, String payloadJson);

    void refundStatus(long bookingId, String payloadJson);

    class BookingRejectedException extends RuntimeException {
        public BookingRejectedException(String message) {
            super(message);
        }
    }

    class BookingUnavailableException extends RuntimeException {
        public BookingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
