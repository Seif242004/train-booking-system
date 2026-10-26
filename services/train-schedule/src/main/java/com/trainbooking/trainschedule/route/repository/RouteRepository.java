package com.trainbooking.trainschedule.route.repository;

import com.trainbooking.trainschedule.route.model.Route;
import com.trainbooking.trainschedule.route.model.RouteStop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Data access for the route and route_stop tables. Parameterised SQL only. */
@Repository
public class RouteRepository {

    private static final String STOPS_SELECT =
            "SELECT rs.route_id, rs.stop_index, rs.station_id, s.code, s.name, s.city "
                    + "FROM route_stop rs JOIN station s ON s.id = rs.station_id ";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batchTemplate;

    public RouteRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batchTemplate) {
        this.jdbc = jdbc;
        this.batchTemplate = batchTemplate;
    }

    /** Every route with its stops in travel order: two queries instead of one per route. */
    public List<Route> findAll() {
        Map<Long, String> names = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name FROM route ORDER BY name, id").query((rs, row) -> {
            names.put(rs.getLong("id"), rs.getString("name"));
            return null;
        }).list();

        Map<Long, List<RouteStop>> stopsByRoute = new LinkedHashMap<>();
        jdbc.sql(STOPS_SELECT + "ORDER BY rs.route_id, rs.stop_index").query((rs, row) -> {
            stopsByRoute.computeIfAbsent(rs.getLong("route_id"), id -> new ArrayList<>()).add(stopFrom(rs));
            return null;
        }).list();

        List<Route> routes = new ArrayList<>();
        names.forEach((id, name) -> routes.add(new Route(id, name, stopsByRoute.getOrDefault(id, List.of()))));
        return routes;
    }

    public Optional<Route> findById(long id) {
        Optional<String> name = jdbc.sql("SELECT name FROM route WHERE id = :id").param("id", id).query(String.class).optional();
        return name.map(n -> new Route(id, n, stopsOf(id)));
    }

    public List<RouteStop> stopsOf(long routeId) {
        return jdbc.sql(STOPS_SELECT + "WHERE rs.route_id = :routeId ORDER BY rs.stop_index")
                .param("routeId", routeId)
                .query((rs, row) -> stopFrom(rs))
                .list();
    }

    /** Inserts the route row and returns its generated id. The stops are inserted separately, in the same transaction. */
    public long insert(String name) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO route (name) VALUES (:name)").param("name", name).update(keys, "id");
        return keys.getKey().longValue();
    }

    /** @return true if a row was updated, false if the id does not exist */
    public boolean updateName(long id, String name) {
        return jdbc.sql("UPDATE route SET name = :name WHERE id = :id").param("name", name).param("id", id).update() == 1;
    }

    /** Stop index is the position in the list: the first station is stop 0. One JDBC batch. */
    public void insertStops(long routeId, List<Long> stationIds) {
        SqlParameterSource[] batch = new SqlParameterSource[stationIds.size()];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = new MapSqlParameterSource()
                    .addValue("routeId", routeId)
                    .addValue("stopIndex", i)
                    .addValue("stationId", stationIds.get(i));
        }
        batchTemplate.batchUpdate(
                "INSERT INTO route_stop (route_id, stop_index, station_id) VALUES (:routeId, :stopIndex, :stationId)", batch);
    }

    public void deleteStops(long routeId) {
        jdbc.sql("DELETE FROM route_stop WHERE route_id = :routeId").param("routeId", routeId).update();
    }

    /** Deletes the route; its stops and fares go with it through ON DELETE CASCADE. */
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM route WHERE id = :id").param("id", id).update() == 1;
    }

    private static RouteStop stopFrom(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RouteStop(rs.getInt("stop_index"), rs.getLong("station_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("city"));
    }
}
