package com.trainbooking.payment.testsupport;

import com.trainbooking.payment.outbox.service.BookingNotifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stands in for Booking and Seat: records what it was told, and can be switched off or made to refuse. */
public class FakeBookingNotifier implements BookingNotifier {

    public record Call(String kind, long bookingId, String payload) {
    }

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean down;
    private volatile boolean rejecting;
    private volatile PaymentAnswer answer = PaymentAnswer.CONFIRMED;

    public void reset() {
        calls.clear();
        down = false;
        rejecting = false;
        answer = PaymentAnswer.CONFIRMED;
    }

    public void down(boolean down) {
        this.down = down;
    }

    /** Booking answers 404/400: it will never accept the message. */
    public void rejecting(boolean rejecting) {
        this.rejecting = rejecting;
    }

    public void answers(PaymentAnswer answer) {
        this.answer = answer;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public PaymentAnswer paymentResult(long bookingId, String payloadJson) {
        check();
        calls.add(new Call("payment-result", bookingId, payloadJson));
        return answer;
    }

    @Override
    public void refundStatus(long bookingId, String payloadJson) {
        check();
        calls.add(new Call("refund-status", bookingId, payloadJson));
    }

    private void check() {
        if (down) {
            throw new BookingUnavailableException("Booking is down (test)", null);
        }
        if (rejecting) {
            throw new BookingRejectedException("Booking answered 404 (test)");
        }
    }
}
