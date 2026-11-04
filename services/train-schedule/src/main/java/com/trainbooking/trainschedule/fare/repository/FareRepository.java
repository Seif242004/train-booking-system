package com.trainbooking.trainschedule.fare.repository;

import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.fare.model.Fare;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for the fare table. Parameterised SQL only. */
// @Repository: data-access bean of the fare table. Primary key (route_id, segment_index, coach_class) makes a repeat impossible.
@Repository
public class FareRepository {

    // Hand-written RowMapper: one result row -> Fare (amount as BigDecimal, never double, for money).
    private static final RowMapper<Fare> FARE = (rs, row) -> new Fare(
            rs.getInt("segment_index"),
            CoachClass.valueOf(rs.getString("coach_class")),
            rs.getBigDecimal("amount"),
            rs.getString("currency"));

    private final JdbcClient jdbc;
    /** Used for the JDBC batch insert of the whole fare list. */
    private final NamedParameterJdbcTemplate batchTemplate;

    public FareRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batchTemplate) {
        this.jdbc = jdbc;
        this.batchTemplate = batchTemplate;
    }

    /** The fares of a route ordered by segment, then class (FIRST, SECOND, THIRD sort alphabetically). */
    public List<Fare> findByRoute(long routeId) {
        return jdbc.sql("SELECT segment_index, coach_class, amount, currency FROM fare WHERE route_id = :routeId "
                        + "ORDER BY segment_index, coach_class")
                .param("routeId", routeId)
                .query(FARE)
                .list();
    }

    /** How many fares a route has. Used before the stops of a route change and before a delete (for the audit row). */
    public int countByRoute(long routeId) {
        return jdbc.sql("SELECT COUNT(*) FROM fare WHERE route_id = :routeId").param("routeId", routeId).query(Integer.class).single();
    }

    /** @return the number of fares removed */
    public int deleteByRoute(long routeId) {
        return jdbc.sql("DELETE FROM fare WHERE route_id = :routeId").param("routeId", routeId).update();
    }

    /** Inserts all fares in one JDBC batch, inside the caller's transaction. */
    public void insertAll(long routeId, List<Fare> fares) {
        SqlParameterSource[] batch = fares.stream()
                .map(fare -> new MapSqlParameterSource()
                        .addValue("routeId", routeId)
                        .addValue("segmentIndex", fare.segmentIndex())
                        .addValue("coachClass", fare.coachClass().name())
                        .addValue("amount", fare.amount())
                        .addValue("currency", fare.currency()))
                .toArray(SqlParameterSource[]::new);
        batchTemplate.batchUpdate(
                "INSERT INTO fare (route_id, segment_index, coach_class, amount, currency) "
                        + "VALUES (:routeId, :segmentIndex, :coachClass, :amount, :currency)",
                batch);
    }
}
