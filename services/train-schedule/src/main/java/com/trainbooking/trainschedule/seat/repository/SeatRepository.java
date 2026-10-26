package com.trainbooking.trainschedule.seat.repository;

import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.seat.model.Seat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for the seat table. Parameterised SQL only. */
@Repository
public class SeatRepository {

    // Explicit mapping: the columns seat_row and seat_column are the API fields row and column (reserved words in Oracle).
    private static final RowMapper<Seat> SEAT = (rs, row) -> new Seat(
            rs.getLong("id"),
            rs.getLong("coach_id"),
            rs.getString("seat_number"),
            rs.getInt("seat_row"),
            rs.getString("seat_column"),
            CoachClass.valueOf(rs.getString("class")));

    private static final String COLUMNS = "id, coach_id, seat_number, seat_row, seat_column, class";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batchTemplate;

    public SeatRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batchTemplate) {
        this.jdbc = jdbc;
        this.batchTemplate = batchTemplate;
    }

    /** The seats of a coach, in seat-map order: by row, then by column. */
    public List<Seat> findByCoach(long coachId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM seat WHERE coach_id = :coachId ORDER BY seat_row, seat_column")
                .param("coachId", coachId)
                .query(SEAT)
                .list();
    }

    public Optional<Seat> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM seat WHERE id = :id").param("id", id).query(SEAT).optional();
    }

    public int countByCoach(long coachId) {
        return jdbc.sql("SELECT COUNT(*) FROM seat WHERE coach_id = :coachId").param("coachId", coachId).query(Integer.class).single();
    }

    /**
     * Inserts all seats in one JDBC batch. The caller runs this inside a transaction, so a failing row (a duplicate
     * number or position, ORA-00001) rolls the whole batch back: the request is all-or-nothing.
     */
    public void insertAll(long coachId, CoachClass coachClass, List<Seat> newSeats) {
        SqlParameterSource[] batch = newSeats.stream()
                .map(seat -> new MapSqlParameterSource()
                        .addValue("coachId", coachId)
                        .addValue("number", seat.seatNumber())
                        .addValue("row", seat.row())
                        .addValue("column", seat.column())
                        .addValue("class", coachClass.name()))
                .toArray(SqlParameterSource[]::new);
        batchTemplate.batchUpdate(
                "INSERT INTO seat (coach_id, seat_number, seat_row, seat_column, class) "
                        + "VALUES (:coachId, :number, :row, :column, :class)",
                batch);
    }

    /** @return true if a row was updated, false if the id does not exist */
    public boolean update(long id, String seatNumber, int row, String column) {
        return jdbc.sql("UPDATE seat SET seat_number = :number, seat_row = :row, seat_column = :column WHERE id = :id")
                .param("number", seatNumber)
                .param("row", row)
                .param("column", column)
                .param("id", id)
                .update() == 1;
    }

    /** Sets the class of every seat of a coach. Used when the coach's class changes. @return the seats changed */
    public int updateClassByCoach(long coachId, CoachClass coachClass) {
        return jdbc.sql("UPDATE seat SET class = :class WHERE coach_id = :coachId")
                .param("class", coachClass.name())
                .param("coachId", coachId)
                .update();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM seat WHERE id = :id").param("id", id).update() == 1;
    }
}
