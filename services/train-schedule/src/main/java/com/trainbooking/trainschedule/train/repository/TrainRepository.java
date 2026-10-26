package com.trainbooking.trainschedule.train.repository;

import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.model.TrainStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Data access for the train table. Parameterised SQL only: values are always bound, never concatenated. */
@Repository
public class TrainRepository {

    // Explicit mapping because the column train_number is the API field number (NUMBER is reserved in Oracle).
    private static final RowMapper<Train> TRAIN = (rs, row) -> new Train(
            rs.getLong("id"),
            rs.getString("train_number"),
            rs.getString("name"),
            rs.getString("type"),
            TrainStatus.valueOf(rs.getString("status")));

    private static final String COLUMNS = "id, train_number, name, type, status";

    private final JdbcClient jdbc;

    public TrainRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lists trains, optionally filtered (FR-A2). Number and name match a case-insensitive "contains"; status is exact.
     * Only the fixed condition fragments below are appended to the SQL; every value is a bound parameter.
     */
    public List<Train> search(String number, String name, TrainStatus status) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM train WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (number != null) {
            sql.append(" AND UPPER(train_number) LIKE :number ESCAPE '\\'");
            params.put("number", containsPattern(number));
        }
        if (name != null) {
            sql.append(" AND UPPER(name) LIKE :name ESCAPE '\\'");
            params.put("name", containsPattern(name));
        }
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
        sql.append(" ORDER BY train_number");
        return jdbc.sql(sql.toString()).params(params).query(TRAIN).list();
    }

    public Optional<Train> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM train WHERE id = :id").param("id", id).query(TRAIN).optional();
    }

    /** A duplicate number violates the unique constraint (ORA-00001), reported by Spring as DuplicateKeyException. */
    public Train insert(String number, String name, String type, TrainStatus status) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO train (train_number, name, type, status) VALUES (:number, :name, :type, :status)")
                .param("number", number)
                .param("name", name)
                .param("type", type)
                .param("status", status.name())
                .update(keys, "id"); // name the key column: Oracle would otherwise return the ROWID
        return new Train(keys.getKey().longValue(), number, name, type, status);
    }

    /** @return true if a row was updated, false if the id does not exist */
    public boolean update(long id, String number, String name, String type, TrainStatus status) {
        return jdbc.sql("UPDATE train SET train_number = :number, name = :name, type = :type, status = :status WHERE id = :id")
                .param("number", number)
                .param("name", name)
                .param("type", type)
                .param("status", status.name())
                .param("id", id)
                .update() == 1;
    }

    /** Deletes the train; the coaches and their seats go with it through ON DELETE CASCADE. */
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM train WHERE id = :id").param("id", id).update() == 1;
    }

    /** Used only to describe what a delete removed, in the audit log. */
    public int countCoaches(long trainId) {
        return jdbc.sql("SELECT COUNT(*) FROM coach WHERE train_id = :id").param("id", trainId).query(Integer.class).single();
    }

    public int countSeats(long trainId) {
        return jdbc.sql("SELECT COUNT(*) FROM seat WHERE coach_id IN (SELECT id FROM coach WHERE train_id = :id)")
                .param("id", trainId)
                .query(Integer.class)
                .single();
    }

    /** Turns user text into a LIKE pattern that matches it anywhere, treating % _ and \ literally. */
    static String containsPattern(String text) {
        String escaped = text.strip().toUpperCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
