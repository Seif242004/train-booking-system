package com.trainbooking.trainschedule.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes the admin audit trail (FR-A10). Called inside the same transaction as the change it records. */
@Repository
public class AuditLogRepository {

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param adminId  the admin's Keycloak user id (JWT sub)
     * @param action   CREATE, UPDATE or DELETE
     * @param diffJson the values before and after the change, as JSON
     */
    public void record(String adminId, String entity, long entityId, String action, String diffJson) {
        jdbc.sql("INSERT INTO audit_log (admin_id, entity, entity_id, action, diff) "
                        + "VALUES (:adminId, :entity, :entityId, :action, :diff)")
                .param("adminId", adminId)
                .param("entity", entity)
                .param("entityId", entityId)
                .param("action", action)
                .param("diff", diffJson)
                .update();
    }
}
