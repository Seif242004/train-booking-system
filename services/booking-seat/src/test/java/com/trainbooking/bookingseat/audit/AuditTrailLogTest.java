package com.trainbooking.bookingseat.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** The audit trail also writes a structured log line (for OpenObserve) with the actor and the booking and trip ids, after the commit. */
class AuditTrailLogTest {

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final Logger audit = (Logger) LoggerFactory.getLogger("audit");
    private final AuditTrail trail = new AuditTrail(mock(AuditLogRepository.class), JsonMapper.builder().build());

    @BeforeEach
    void capture() {
        lines.start();
        audit.addAppender(lines);
        MDC.clear();
    }

    @AfterEach
    void release() {
        audit.detachAppender(lines);
        TransactionSynchronizationManager.clear();
        MDC.clear();
    }

    @Test
    void aBookingChange_isLoggedWithTheActor_theBookingId_andTheTripId() {
        trail.record("admin-sub", "booking", 77, "CANCEL", Map.of("tripId", 9L, "reason", "Strike"));

        assertThat(lines.list).singleElement().satisfies(line -> {
            assertThat(line.getMDCPropertyMap()).containsEntry("actor", "admin-sub").containsEntry("bookingId", "77").containsEntry("tripId", "9")
                    .containsEntry("action", "CANCEL").containsEntry("entity", "booking");
            assertThat(line.getFormattedMessage()).isEqualTo("audit CANCEL booking 77 by admin-sub");
        });
        assertThat(MDC.getCopyOfContextMap()).as("the logging context is left as it was").isNullOrEmpty();
    }

    @Test
    void aTripChange_carriesTheTripId() {
        trail.record("admin-sub", "trip", 5, "CANCEL_REQUEST", Map.of("reason", "x"));

        assertThat(lines.list).singleElement().satisfies(line -> assertThat(line.getMDCPropertyMap()).containsEntry("tripId", "5").doesNotContainKey("bookingId"));
    }

    @Test
    void insideATransaction_nothingIsLoggedUntilItCommits_andNothingWhenItRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        trail.record("admin-sub", "booking", 1, "CANCEL", Map.of("tripId", 9L));
        assertThat(lines.list).isEmpty();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(lines.list).hasSize(1);

        lines.list.clear();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        trail.record("admin-sub", "booking", 2, "CANCEL", Map.of("tripId", 9L));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(lines.list).as("a rolled-back change leaves no audit line").isEmpty();
    }
}
