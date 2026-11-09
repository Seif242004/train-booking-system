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
// @RestController = @Controller + @ResponseBody: each method answers an HTTP request and its return value is written as JSON.
// No logic here: the three methods only hand the request to PaymentService. Caller: Booking and Seat (PaymentClient), with the API key.
@RestController
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /** Creates (or returns) the payment of a booking: the client secret for the embedded Stripe form. */
    // @PostMapping: answers POST on this path. @Valid makes Spring check the Bean Validation rules of CreatePaymentRequest (a failure is
    // a 400 validation_failed, see GlobalExceptionHandler). @RequestBody reads the JSON body into the record.
    // Errors from the service: 409 payment_closed / already_paid / payment_in_progress / payment_outdated / amount_too_small, 503 stripe_*.
    @PostMapping("/internal/payments")
    PaymentSession create(@Valid @RequestBody CreatePaymentRequest request) {
        return service.create(request);
    }

    /** The hold ended: cancel the open payment for good (Stripe first, then the caller releases the seats). */
    // {bookingId} is a path variable, bound by @PathVariable. The answer is {"outcome": NONE | CANCELLED | SUCCEEDED | PROCESSING}.
    @PostMapping("/internal/payments/{bookingId}/cancel")
    Map<String, CancelOutcome> cancel(@PathVariable long bookingId) {
        return Map.of("outcome", service.cancelPending(bookingId));
    }

    /** The booking changes: cancel the open payment so its amount never differs from the booking's; a new one may be created later. */
    // Same answer shape as cancel; the difference is that the payment may be created again afterwards (a new revision).
    @PostMapping("/internal/payments/{bookingId}/invalidate")
    Map<String, CancelOutcome> invalidate(@PathVariable long bookingId) {
        return Map.of("outcome", service.invalidate(bookingId));
    }
}
