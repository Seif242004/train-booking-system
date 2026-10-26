package com.trainbooking.trainschedule.trip.repository;

import com.trainbooking.trainschedule.trip.model.Trip;
import com.trainbooking.trainschedule.trip.model.TripStatus;
import com.trainbooking.trainschedule.trip.model.TripStop;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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

/**
 * Data access for the trip and trip_stop tables, and for trip search. Parameterised SQL only. Times are stored as
 * TIMESTAMP WITH TIME ZONE and read back as local date-times in the service time zone.
 */
@Repository
public class TripRepository {

    /** The data of one stop to save: times already converted to the zone offset. */
    public record StopRow(int stopIndex, long stationId, OffsetDateTime arrival, OffsetDateTime departure) {
    }

    /** One raw row of the search query; the service adds the stations and the fares. */
    public record SearchRow(long tripId, String trainNumber, String trainName, String trainType, long routeId, String routeName,
                            int fromStopIndex, int toStopIndex, LocalDateTime departure, LocalDateTime arrival) {
    }

    private record Head(long id, long trainId, String trainNumber, String trainName, long routeId, String routeName,
                        LocalDate departureDate, TripStatus status) {
    }

    private static final String HEAD_SELECT =
            "SELECT t.id, t.train_id, tr.train_number, tr.name AS train_name, t.route_id, r.name AS route_name, "
                    + "t.departure_date, t.status "
                    + "FROM trip t JOIN train tr ON tr.id = t.train_id JOIN route r ON r.id = t.route_id "
                    + "LEFT JOIN trip_stop first_stop ON first_stop.trip_id = t.id AND first_stop.stop_index = 0 ";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batchTemplate;
    private final ZoneId zone;

    public TripRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batchTemplate, ZoneId zone) {
        this.jdbc = jdbc;
        this.batchTemplate = batchTemplate;
        this.zone = zone;
    }

    /** All trips by their first departure, optionally only those that leave on one date. */
    public List<Trip> findAll(LocalDate date) {
        List<Head> heads = date == null
                ? jdbc.sql(HEAD_SELECT + "ORDER BY first_stop.departure_time, t.id").query((rs, row) -> head(rs)).list()
                : jdbc.sql(HEAD_SELECT + "WHERE t.departure_date = :date ORDER BY first_stop.departure_time, t.id")
                        .param("date", date).query((rs, row) -> head(rs)).list();
        return withStops(heads);
    }

    public Optional<Trip> findById(long id) {
        List<Head> heads = jdbc.sql(HEAD_SELECT + "WHERE t.id = :id").param("id", id).query((rs, row) -> head(rs)).list();
        return withStops(heads).stream().findFirst();
    }

    public long insert(long trainId, long routeId, LocalDate departureDate) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO trip (train_id, route_id, departure_date) VALUES (:trainId, :routeId, :date)")
                .param("trainId", trainId)
                .param("routeId", routeId)
                .param("date", departureDate)
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    /** @return true if a row was updated, false if the id does not exist */
    public boolean update(long id, long trainId, long routeId, LocalDate departureDate) {
        return jdbc.sql("UPDATE trip SET train_id = :trainId, route_id = :routeId, departure_date = :date WHERE id = :id")
                .param("trainId", trainId)
                .param("routeId", routeId)
                .param("date", departureDate)
                .param("id", id)
                .update() == 1;
    }

    /** Inserts all stops of a trip in one JDBC batch, inside the caller's transaction. */
    public void insertStops(long tripId, List<StopRow> stops) {
        SqlParameterSource[] batch = stops.stream()
                .map(stop -> new MapSqlParameterSource()
                        .addValue("tripId", tripId)
                        .addValue("stopIndex", stop.stopIndex())
                        .addValue("stationId", stop.stationId())
                        // The SQL type is given so that an empty time (first or last stop) is bound as a timestamp NULL.
                        .addValue("arrival", stop.arrival(), Types.TIMESTAMP_WITH_TIMEZONE)
                        .addValue("departure", stop.departure(), Types.TIMESTAMP_WITH_TIMEZONE))
                .toArray(SqlParameterSource[]::new);
        batchTemplate.batchUpdate(
                "INSERT INTO trip_stop (trip_id, stop_index, station_id, arrival_time, departure_time) "
                        + "VALUES (:tripId, :stopIndex, :stationId, :arrival, :departure)",
                batch);
    }

    public void deleteStops(long tripId) {
        jdbc.sql("DELETE FROM trip_stop WHERE trip_id = :tripId").param("tripId", tripId).update();
    }

    /** Deletes the trip; its stops go with it through ON DELETE CASCADE. */
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM trip WHERE id = :id").param("id", id).update() == 1;
    }

    public int countByRoute(long routeId) {
        return jdbc.sql("SELECT COUNT(*) FROM trip WHERE route_id = :routeId").param("routeId", routeId).query(Integer.class).single();
    }

    /**
     * Trips that make the journey from one station to another and leave the origin within [dayStart, dayEnd). Only ACTIVE
     * trips (cancelled trips are never returned), and only where the origin comes before the destination on the trip's route.
     */
    public List<SearchRow> search(long fromStationId, long toStationId, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        return jdbc.sql("""
                        SELECT t.id AS trip_id, tr.train_number, tr.name AS train_name, tr.type AS train_type,
                               r.id AS route_id, r.name AS route_name,
                               fs.stop_index AS from_index, ts.stop_index AS to_index,
                               fs.departure_time AS departure_time, ts.arrival_time AS arrival_time
                          FROM trip t
                          JOIN trip_stop fs ON fs.trip_id = t.id AND fs.station_id = :fromId
                          JOIN trip_stop ts ON ts.trip_id = t.id AND ts.station_id = :toId AND ts.stop_index > fs.stop_index
                          JOIN train tr ON tr.id = t.train_id
                          JOIN route r ON r.id = t.route_id
                         WHERE t.status = 'ACTIVE'
                           AND fs.departure_time >= :dayStart AND fs.departure_time < :dayEnd
                         ORDER BY fs.departure_time, t.id
                        """)
                .param("fromId", fromStationId)
                .param("toId", toStationId)
                .param("dayStart", dayStart)
                .param("dayEnd", dayEnd)
                .query((rs, row) -> new SearchRow(
                        rs.getLong("trip_id"), rs.getString("train_number"), rs.getString("train_name"), rs.getString("train_type"),
                        rs.getLong("route_id"), rs.getString("route_name"), rs.getInt("from_index"), rs.getInt("to_index"),
                        local(rs, "departure_time"), local(rs, "arrival_time")))
                .list();
    }

    /**
     * The first departure from the origin, not before {@code notBefore}, of an ACTIVE trip that goes on to the destination;
     * empty if there is none. The caller takes the date of it (in the service zone) as "the next date with a trip".
     */
    public Optional<LocalDateTime> findNextDeparture(long fromStationId, long toStationId, OffsetDateTime notBefore) {
        return jdbc.sql("""
                        SELECT fs.departure_time AS departure_time
                          FROM trip t
                          JOIN trip_stop fs ON fs.trip_id = t.id AND fs.station_id = :fromId
                          JOIN trip_stop ts ON ts.trip_id = t.id AND ts.station_id = :toId AND ts.stop_index > fs.stop_index
                         WHERE t.status = 'ACTIVE' AND fs.departure_time >= :notBefore
                         ORDER BY fs.departure_time
                         FETCH FIRST 1 ROWS ONLY
                        """)
                .param("fromId", fromStationId)
                .param("toId", toStationId)
                .param("notBefore", notBefore)
                .query((rs, row) -> local(rs, "departure_time"))
                .optional();
    }

    // ---- reading

    private static Head head(ResultSet rs) throws SQLException {
        return new Head(rs.getLong("id"), rs.getLong("train_id"), rs.getString("train_number"), rs.getString("train_name"),
                rs.getLong("route_id"), rs.getString("route_name"), rs.getObject("departure_date", LocalDate.class),
                TripStatus.valueOf(rs.getString("status")));
    }

    /** Loads the stops of all the given trips with one query and assembles the trips, keeping the order of the heads. */
    private List<Trip> withStops(List<Head> heads) {
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<Long, List<TripStop>> stopsByTrip = findStops(heads.stream().map(Head::id).toList());

        List<Trip> trips = new ArrayList<>();
        for (Head h : heads) {
            trips.add(new Trip(h.id(), h.trainId(), h.trainNumber(), h.trainName(), h.routeId(), h.routeName(), h.departureDate(),
                    h.status(), stopsByTrip.getOrDefault(h.id(), List.of())));
        }
        return trips;
    }

    /** The stops of the given trips, in stop order, loaded with one query and grouped by trip id. */
    public Map<Long, List<TripStop>> findStops(List<Long> tripIds) {
        Map<Long, List<TripStop>> stopsByTrip = new HashMap<>();
        if (tripIds.isEmpty()) {
            return stopsByTrip;
        }
        jdbc.sql("SELECT ts.trip_id, ts.stop_index, ts.station_id, s.code, s.name, ts.arrival_time, ts.departure_time "
                        + "FROM trip_stop ts JOIN station s ON s.id = ts.station_id "
                        + "WHERE ts.trip_id IN (:ids) ORDER BY ts.trip_id, ts.stop_index")
                .param("ids", tripIds)
                .query((rs, row) -> {
                    stopsByTrip.computeIfAbsent(rs.getLong("trip_id"), id -> new ArrayList<>()).add(new TripStop(
                            rs.getInt("stop_index"), rs.getLong("station_id"), rs.getString("code"), rs.getString("name"),
                            local(rs, "arrival_time"), local(rs, "departure_time")));
                    return null;
                })
                .list();
        return stopsByTrip;
    }

    private LocalDateTime local(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.atZoneSameInstant(zone).toLocalDateTime();
    }
}
