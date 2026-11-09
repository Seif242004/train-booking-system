package com.trainbooking.notification.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes the admin audit trail (FR-A10). Called inside the same transaction as the change it records. */
// @Repository marks the class as a data-access bean: Spring creates one instance and translates JDBC errors into DataAccessException types.
@Repository
public class AuditLogRepository {

    // JdbcClient is Spring's small fluent API for plain SQL with named parameters (":name"); no JPA, so the SQL stays visible.
    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param adminId  the admin's Keycloak user id (JWT sub)
     * @param action   CREATE, UPDATE or DELETE
     * @param diffJson the values before and after the change, as JSON
     */
    /*
     * Inserts one audit_log row (table from V1__create_notification_tables.sql). There is no transaction of its own: it joins the one
      * opened by the caller (AuditTrail is called inside NotificationService.accept), so the row and the change commit or roll back
      * together.
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
