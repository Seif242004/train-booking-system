package com.trainbooking.trainschedule.coach.repository;

import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Data access for the coach table. Parameterised SQL only. */
// @Repository: data-access bean of the coach feature.
@Repository
public class CoachRepository {

    // Hand-written RowMapper: converts one result row to a Coach (the class column is read back into the CoachClass enum).
    private static final RowMapper<Coach> COACH = (rs, row) -> new Coach(
            rs.getLong("id"),
            rs.getLong("train_id"),
            rs.getString("code"),
            CoachClass.valueOf(rs.getString("class")),
            rs.getInt("position"),
            rs.getInt("standing_capacity"));

    private static final String COLUMNS = "id, train_id, code, class, position, standing_capacity";

    /** Spring's fluent JDBC helper. */
    private final JdbcClient jdbc;

    public CoachRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The coaches of a train in their order in the train (FR-A5). */
    public List<Coach> findByTrain(long trainId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM coach WHERE train_id = :trainId ORDER BY position")
                .param("trainId", trainId)
                .query(COACH)
                .list();
    }

    /** One coach by id, or empty. */
    public Optional<Coach> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM coach WHERE id = :id").param("id", id).query(COACH).optional();
    }

    /**
     * A duplicate code or position in the same train violates a unique constraint (ORA-00001); Spring reports it as
     * DuplicateKeyException and the message names the constraint.
     */
    public Coach insert(long trainId, String code, CoachClass coachClass, int position, int standingCapacity) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO coach (train_id, code, class, position, standing_capacity) "
                        + "VALUES (:trainId, :code, :class, :position, :standing)")
                .param("trainId", trainId)
                .param("code", code)
                .param("class", coachClass.name())
                .param("position", position)
                .param("standing", standingCapacity)
                .update(keys, "id");
        return new Coach(keys.getKey().longValue(), trainId, code, coachClass, position, standingCapacity);
    }

    /** @return true if a row was updated, false if the id does not exist */
    // The UPDATE touches one row by id; a duplicate code or position again raises ORA-00001 (constraints uk_coach_code, uk_coach_position).
    public boolean update(long id, String code, CoachClass coachClass, int position, int standingCapacity) {
        return jdbc.sql("UPDATE coach SET code = :code, class = :class, position = :position, standing_capacity = :standing "
                        + "WHERE id = :id")
                .param("code", code)
                .param("class", coachClass.name())
                .param("position", position)
                .param("standing", standingCapacity)
                .param("id", id)
                .update() == 1;
    }

    /** Deletes the coach; its seats go with it through ON DELETE CASCADE. */
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM coach WHERE id = :id").param("id", id).update() == 1;
    }
}
