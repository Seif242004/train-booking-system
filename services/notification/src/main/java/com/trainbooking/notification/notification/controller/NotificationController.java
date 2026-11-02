package com.trainbooking.notification.notification.controller;

import com.trainbooking.notification.notification.model.NotificationRequest;
import com.trainbooking.notification.notification.service.NotificationService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service endpoint used by Booking and Seat (shared API key). Users never call Notification. */
@RestController
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** Stores the email to send: 202 Accepted (it is sent afterwards, with retries), or 200 if the same dedupe key was accepted before. */
    @PostMapping("/internal/notifications")
    ResponseEntity<Map<String, Object>> send(@Valid @RequestBody NotificationRequest request) {
        NotificationService.Accepted accepted = service.accept(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", accepted.email().id());
        body.put("status", accepted.email().status().name());
        return ResponseEntity.status(accepted.created() ? 202 : 200).body(body);
    }
}
