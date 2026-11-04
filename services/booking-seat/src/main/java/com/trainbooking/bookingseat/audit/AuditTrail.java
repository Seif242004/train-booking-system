package com.trainbooking.bookingseat.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records admin changes (FR-A10). Services call it inside the transaction of the change itself, so the audit row
 * and the change are saved together or not at all.
 */
// @Component = a general Spring bean, created once (singleton) and injected through the constructor into the services that audit changes.
@Component
public class AuditTrail {

    /** Logger name "audit": the same facts as the audit_log table, as structured log lines for OpenObserve. */
    private static final Logger log = LoggerFactory.getLogger("audit");

    private final AuditLogRepository repository;
    private final JsonMapper jsonMapper;

    public AuditTrail(AuditLogRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /** A change of one entity: the diff holds its values {@code before} and {@code after} (null for create or delete). */
    /*
     * Every admin action and every booking state change goes through here. The audit_log row is written inside the caller's transaction, so
     * it exists if and only if the change was committed. The structured log line (for OpenObserve) is emitted only after the commit.
     */
    public void change(String adminId, String entity, long entityId, String action, Object before, Object after) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("before", before);
        diff.put("after", after);
        record(adminId, entity, entityId, action, diff);
    }

    /** A change with a free-form diff, for example a delete that also removed child rows. */
    public void record(String adminId, String entity, long entityId, String action, Map<String, Object> diff) {
        String json = jsonMapper.writeValueAsString(diff);
        repository.record(adminId, entity, entityId, action, json);
        logAfterCommit(adminId, entity, entityId, action, json, diff);
    }

    /**
     * The log line is written once the transaction has committed, never before: a change that rolls back leaves an audit_log row of
     * neither kind. Outside a transaction it is written at once.
     */
    private static void logAfterCommit(String actor, String entity, long entityId, String action, String json, Map<String, Object> diff) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Registers a callback with the running transaction: afterCommit() runs only if the transaction really commits. On a rollback
            // nothing
            // is logged, so the log never claims a change that did not happen.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emit(actor, entity, entityId, action, json, diff);
                }
            });
        } else {
            emit(actor, entity, entityId, action, json, diff);
        }
    }

    /** The ids go into the MDC, so every log line (and its OpenObserve record) carries actor, booking id and trip id as fields. */
    private static void emit(String actor, String entity, long entityId, String action, String json, Map<String, Object> diff) {
        // MDC (Mapped Diagnostic Context) is a per-thread map whose entries the logging backend prints as fields of the JSON log line.
        // The map is copied first and restored in the finally block, so the audit fields do not leak into later log lines of the same
        // thread.
        Map<String, String> before = MDC.getCopyOfContextMap();
        try {
            MDC.put("actor", String.valueOf(actor));
            MDC.put("entity", entity);
            MDC.put("entityId", String.valueOf(entityId));
            MDC.put("action", action);
            MDC.put("diff", json);
            if ("booking".equals(entity)) {
                MDC.put("bookingId", String.valueOf(entityId));
            }
            // A trip audit entry uses its own id as trip id; any other entry may carry a "tripId" key in its diff (for example a booking
            // change).
            Object tripId = "trip".equals(entity) ? (Object) entityId : diff.get("tripId");
            if (tripId != null) {
                MDC.put("tripId", String.valueOf(tripId));
            }
            log.info("audit {} {} {} by {}", action, entity, entityId, actor);
        } finally {
            if (before == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(before);
            }
        }
    }
}
