package com.trainbooking.trainschedule.station.repository;

import com.trainbooking.trainschedule.station.model.Station;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Data access for the station table. Plain parameterised SQL only: no value is ever concatenated into a statement. */
@Repository
public class StationRepository {

    private final JdbcClient jdbc;

    public StationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Station> findAll() {
        return jdbc.sql("SELECT id, code, name, city FROM station ORDER BY code")
                .query(Station.class)
                .list();
    }

    public Optional<Station> findById(long id) {
        return jdbc.sql("SELECT id, code, name, city FROM station WHERE id = :id")
                .param("id", id)
                .query(Station.class)
                .optional();
    }

    /**
     * Inserts a station and returns it with its generated id. A duplicate code violates the unique constraint
     * (ORA-00001), which Spring reports as DuplicateKeyException.
     */
    public Station insert(String code, String name, String city) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO station (code, name, city) VALUES (:code, :name, :city)")
                .param("code", code)
                .param("name", name)
                .param("city", city)
                .update(keys, "id"); // name the key column: Oracle would otherwise return the ROWID
        return new Station(keys.getKey().longValue(), code, name, city);
    }

    /** @return true if a row was updated, false if the id does not exist */
    public boolean update(long id, String code, String name, String city) {
        return jdbc.sql("UPDATE station SET code = :code, name = :name, city = :city WHERE id = :id")
                .param("code", code)
                .param("name", name)
                .param("city", city)
                .param("id", id)
                .update() == 1;
    }

    /** @return true if a row was deleted, false if the id does not exist */
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM station WHERE id = :id").param("id", id).update() == 1;
    }
}
