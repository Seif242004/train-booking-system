package com.trainbooking.payment.payment.controller;

import com.trainbooking.payment.payment.model.CancelOutcome;
import com.trainbooking.payment.payment.model.CreatePaymentRequest;
import com.trainbooking.payment.payment.model.PaymentSession;
import com.trainbooking.payment.payment.service.PaymentService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service endpoints used by Booking and Seat (shared API key). Users never call Payment. */
@RestController
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /** Creates (or returns) the payment of a booking: the client secret for the embedded Stripe form. */
    @PostMapping("/internal/payments")
    PaymentSession create(@Valid @RequestBody CreatePaymentRequest request) {
        return service.create(request);
    }

    /** The hold ended: cancel the open payment for good (Stripe first, then the caller releases the seats). */
    @PostMapping("/internal/payments/{bookingId}/cancel")
    Map<String, CancelOutcome> cancel(@PathVariable long bookingId) {
        return Map.of("outcome", service.cancelPending(bookingId));
    }

    /** The booking changes: cancel the open payment so its amount never differs from the booking's; a new one may be created later. */
    @PostMapping("/internal/payments/{bookingId}/invalidate")
    Map<String, CancelOutcome> invalidate(@PathVariable long bookingId) {
        return Map.of("outcome", service.invalidate(bookingId));
    }
}
