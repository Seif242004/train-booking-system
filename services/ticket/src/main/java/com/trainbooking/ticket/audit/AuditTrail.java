package com.trainbooking.ticket.audit;

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
