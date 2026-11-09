package com.trainbooking.ticket.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// A repository is the only layer that touches SQL. It holds a JdbcClient (the small fluent JDBC helper of Spring), so the SQL stays
// explicit.
/** Writes the admin audit trail (FR-A10). Called inside the same transaction as the change it records. */
// {@code @Repository} makes this class a Spring bean (found by component scanning) and marks it as data access code.
@Repository
public class AuditLogRepository {

    private final JdbcClient jdbc;

    // Constructor injection: Spring passes the JdbcClient it built from the DataSource. One constructor needs no @Autowired.
    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param adminId  the admin's Keycloak user id (JWT sub)
     * @param action   CREATE, UPDATE or DELETE
     * @param diffJson the values before and after the change, as JSON
     */
    public void record(String adminId, String entity, long entityId, String action, String diffJson) {
        // One plain INSERT with bound parameters (:name), never string concatenation, so no SQL injection. created_at is not listed: the
        // column
        // default (SYSTIMESTAMP) fills it. The id comes from the identity column. The caller's transaction owns this statement, so the
        // audit row
        // is saved or rolled back together with the change it records.
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
