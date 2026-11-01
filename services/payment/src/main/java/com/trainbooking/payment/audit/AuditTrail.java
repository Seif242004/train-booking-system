package com.trainbooking.payment.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records admin changes (FR-A10). Services call it inside the transaction of the change itself, so the audit row
 * and the change are saved together or not at all.
 */
@Component
public class AuditTrail {

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
        repository.record(adminId, entity, entityId, action, jsonMapper.writeValueAsString(diff));
    }
}
