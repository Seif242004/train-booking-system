package com.trainbooking.payment.webhook.controller;

import com.trainbooking.payment.webhook.service.WebhookService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /webhooks/payment}: Stripe's events, forwarded by the Gateway with the body untouched. The signature is computed over
 * the exact bytes of the body, so the body is read raw and never re-serialised.
 */
// Public entry for Stripe (through the Gateway). It has no login: the Stripe-Signature header is the proof (see WebhookService).
@RestController
public class WebhookController {

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    // @RequestBody byte[] reads the body untouched (a signature covers the exact bytes). @RequestHeader reads Stripe-Signature; required
    // = false
    // lets the service answer a missing header itself (400 invalid_signature). Answer 200 {received, result: HANDLED | DUPLICATE |
    // IGNORED}.
    @PostMapping("/webhooks/payment")
    ResponseEntity<Map<String, Object>> receive(@RequestBody byte[] body, @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        WebhookService.Result result = service.handle(new String(body, StandardCharsets.UTF_8), signature);
        return ResponseEntity.ok(Map.of("received", true, "result", result.name()));
    }
}
