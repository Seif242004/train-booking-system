package com.trainbooking.bookingseat.outbox.service;

import com.trainbooking.bookingseat.outbox.repository.OutboxRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Saturation metrics of the outbox: how many jobs wait and how old the oldest one is. They are observable gauges: the OpenTelemetry
// reader calls the callbacks at each export interval (30 s), so nothing runs between exports. Without the OpenTelemetry agent
// (local runs, tests) GlobalOpenTelemetry is a no-op and the callbacks are never called.
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final OutboxRepository outbox;

    public OutboxMetrics(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @PostConstruct
    void register() {
        Meter meter = GlobalOpenTelemetry.getMeter("booking-seat-outbox");
        meter.gaugeBuilder("tbs.outbox.pending").ofLongs().setUnit("{job}").setDescription("Outbox jobs waiting to be delivered")
                .buildWithCallback(m -> read(backlog -> m.record(backlog.pending())));
        meter.gaugeBuilder("tbs.outbox.oldest_pending_age").setUnit("s").setDescription("Age of the oldest waiting outbox job")
                .buildWithCallback(m -> read(backlog -> m.record(backlog.oldestPendingSince() == null ? 0
                        : Math.max(0, Duration.between(backlog.oldestPendingSince(), OffsetDateTime.now()).toSeconds()))));
    }

    // A failing query must never break the metric export: log and report nothing for this round.
    private void read(java.util.function.Consumer<OutboxRepository.Backlog> sink) {
        try {
            sink.accept(outbox.backlog());
        } catch (RuntimeException e) {
            log.warn("Cannot read the outbox backlog for the metrics: {}", e.getMessage());
        }
    }
}
