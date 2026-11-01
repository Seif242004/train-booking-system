package com.trainbooking.payment.outbox.service;

import com.trainbooking.payment.outbox.model.OutboxMessage;
import com.trainbooking.payment.outbox.repository.OutboxRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the news for Booking and Seat. It must run inside the transaction of the change that causes the news (MANDATORY), so that
 * the change and its message are saved together or not at all: a payment that succeeded can never be forgotten.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock;

    public OutboxWriter(OutboxRepository outbox, JsonMapper json, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentSucceeded(long bookingId, long paymentId, BigDecimal amount, String currency) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", paymentId);
        payload.put("amount", amount);
        payload.put("currency", currency);
        outbox.insert(OutboxMessage.Kind.PAYMENT_RESULT, bookingId, json.writeValueAsString(payload), now());
    }

    /** @param status REFUND_PENDING, REFUNDED or REFUND_FAILED: the names Booking uses for the refund status of a booking */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundChanged(long bookingId, String status) {
        outbox.insert(OutboxMessage.Kind.REFUND_STATUS, bookingId, json.writeValueAsString(Map.of("status", status)), now());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
