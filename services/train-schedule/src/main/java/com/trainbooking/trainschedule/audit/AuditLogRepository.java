package com.trainbooking.trainschedule.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes the admin audit trail (FR-A10) into the {@code audit_log} table. Called inside the same transaction as the change
 * it records, so the row and the change are saved together or not at all. Used only by {@link AuditTrail}.
 */
// @Repository marks this class as the data-access layer: Spring creates one instance (a "bean") and also translates
// low-level SQL exceptions into Spring's DataAccessException family.
@Repository
public class AuditLogRepository {

    /** Spring's thin wrapper over JDBC: we write the SQL ourselves, it binds the named parameters and runs it. */
    private final JdbcClient jdbc;

    /** Constructor injection: Spring passes the JdbcClient bean in when it creates this class. */
    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts one audit row. It joins the caller's transaction (JdbcClient uses the connection bound to the current
     * transaction), so a rollback of the change also removes this row.
     *
     * @param adminId  the admin's Keycloak user id (JWT sub)
     * @param action   CREATE, UPDATE or DELETE
     * @param diffJson the values before and after the change, as JSON
     */
    public void record(String adminId, String entity, long entityId, String action, String diffJson) {
        // Plain INSERT with named parameters (:name). Parameters are always bound, never concatenated, so no SQL injection.
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
