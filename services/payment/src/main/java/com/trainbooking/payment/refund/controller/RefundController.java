package com.trainbooking.payment.refund.controller;

import com.trainbooking.payment.refund.model.Refund;
import com.trainbooking.payment.refund.model.RefundRequest;
import com.trainbooking.payment.refund.service.RefundService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundController {

    private final RefundService service;

    public RefundController(RefundService service) {
        this.service = service;
    }

    /** Requests the full refund of a booking's payment. Asking again returns the same refund. */
    @PostMapping("/internal/refunds")
    Map<String, Object> request(@Valid @RequestBody RefundRequest request) {
        Refund refund = service.request(request.bookingId(), request.reason());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("refundId", refund.id());
        answer.put("bookingId", refund.bookingId());
        answer.put("status", refund.bookingStatus());
        return answer;
    }
}
