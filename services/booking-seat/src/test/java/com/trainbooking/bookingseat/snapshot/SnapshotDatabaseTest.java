package com.trainbooking.bookingseat.snapshot;

import static com.trainbooking.bookingseat.testsupport.Snapshots.replace;
import static com.trainbooking.bookingseat.testsupport.Snapshots.valid;
import static com.trainbooking.bookingseat.testsupport.Snapshots.without;
import static com.trainbooking.bookingseat.testsupport.Snapshots.withCoaches;
import static com.trainbooking.bookingseat.testsupport.Snapshots.withDate;
import static com.trainbooking.bookingseat.testsupport.Snapshots.withFares;
import static com.trainbooking.bookingseat.testsupport.Snapshots.withSeats;
import static com.trainbooking.bookingseat.testsupport.Snapshots.withStops;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.bookingseat.common.CoachClass;
import com.trainbooking.bookingseat.snapshot.model.TripSnapshot;
import com.trainbooking.bookingseat.snapshot.repository.SnapshotRepository;
import com.trainbooking.bookingseat.snapshot.service.SnapshotService;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * The snapshot receiver against a real Oracle schema: the whole snapshot is applied in one transaction, a push can be
 * repeated, and a change that conflicts with holds or bookings is refused without touching anything (FR-A9).
 */
class SnapshotDatabaseTest extends DatabaseTest {

    private static final long TRIP = 8001;

    @Autowired
    JsonMapper json;

    @Autowired
    SnapshotService service;

    @MockitoSpyBean
    SnapshotRepository repository;

    private ResultActions push(long tripId, TripSnapshot snapshot) throws Exception {
        return mvc.perform(put("/internal/trips/{id}/snapshot", tripId).header("X-Internal-Api-Key", INTERNAL_KEY)
                .header("X-Acting-User", "admin-sub").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(snapshot)));
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE trip_id = :t").param("t", TRIP).query(Integer.class).single();
    }

    private String routeName() {
        return jdbc.sql("SELECT route_name FROM trip WHERE id = :t").param("t", TRIP).query(String.class).single();
    }

    // ---- creating the copy ------------------------------------------------------------------------------------------

    @Test
    void theFirstPush_createsTheCopyOfTheTrip_open() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.stops").value(4))
                .andExpect(jsonPath("$.coaches").value(3))
                .andExpect(jsonPath("$.seats").value(4))
                .andExpect(jsonPath("$.fares").value(9))
                .andExpect(jsonPath("$.standingCounters").value(6)); // coaches B and C, 3 segments each; coach A has no standing

        assertThat(jdbc.sql("SELECT status FROM trip WHERE id = :t").param("t", TRIP).query(String.class).single()).isEqualTo("OPEN");
        assertThat(count("trip_stop")).isEqualTo(4);
        assertThat(count("trip_coach")).isEqualTo(3);
        assertThat(count("trip_seat")).isEqualTo(4);
        assertThat(count("trip_fare")).isEqualTo(9);
        assertThat(count("standing_usage")).isEqualTo(6);
        assertThat(jdbc.sql("SELECT capacity FROM standing_usage WHERE trip_id = :t AND coach_id = 20 AND segment_index = 1").param("t", TRIP)
                .query(Integer.class).single()).isEqualTo(10);
        assertThat(count("seat_reservation")).isZero(); // no hold logic yet: the inventory starts empty
    }

    @Test
    void theTimesOfTheStopsAreKeptAsTheSameInstants() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());

        String departure = jdbc.sql("SELECT TO_CHAR(departure_time AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI') FROM trip_stop WHERE trip_id = :t AND stop_index = 0")
                .param("t", TRIP).query(String.class).single();
        assertThat(departure).isEqualTo("2026-11-02T06:00"); // 08:00 at +02:00
    }

    @Test
    void thePushIsWrittenToTheAuditLog_withTheAdminWhoCausedIt() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        push(TRIP, withDate(valid(), valid().departureDate())).andExpect(status().isOk());

        List<String> rows = jdbc.sql("SELECT admin_id || ':' || action || ':' || entity_id FROM audit_log ORDER BY id").query(String.class).list();
        assertThat(rows).containsExactly("admin-sub:CREATE:" + TRIP, "admin-sub:UPDATE:" + TRIP);
    }

    // ---- repeating and changing a push --------------------------------------------------------------------------------

    @Test
    void pushingTheSameSnapshotAgain_changesNothing_andKeepsTheCountersInUse() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        useStanding(TRIP, 20, 1, 4);

        push(TRIP, valid()).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));

        assertThat(count("trip_seat")).isEqualTo(4);
        assertThat(count("standing_usage")).isEqualTo(6);
        assertThat(jdbc.sql("SELECT used FROM standing_usage WHERE trip_id = :t AND coach_id = 20 AND segment_index = 1").param("t", TRIP)
                .query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void aChangedSnapshot_replacesWhatChanged_addsWhatIsNew_andRemovesWhatIsGone() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        TripSnapshot v1 = valid();
        TripSnapshot changed = withSeats(v1, List.of(
                new TripSnapshot.Seat(1L, 10L, "A1X", 1, "A", CoachClass.FIRST),   // renumbered
                new TripSnapshot.Seat(2L, 20L, "B1", 1, "A", CoachClass.SECOND),
                new TripSnapshot.Seat(5L, 20L, "B3", 2, "A", CoachClass.SECOND),   // new
                new TripSnapshot.Seat(4L, 30L, "C1", 1, "A", CoachClass.THIRD)));  // seat 3 is gone
        changed = withCoaches(changed, replace(v1.coaches(), 1, c -> new TripSnapshot.Coach(20L, "B", CoachClass.SECOND, 2, 12))); // 10 to 12
        changed = withFares(changed, replace(v1.fares(), 0, f -> new TripSnapshot.Fare(0, CoachClass.FIRST, new BigDecimal("95.50"), "EGP")));

        push(TRIP, changed).andExpect(status().isOk()).andExpect(jsonPath("$.seats").value(4));

        assertThat(jdbc.sql("SELECT seat_id || ':' || seat_number FROM trip_seat WHERE trip_id = :t ORDER BY seat_id").param("t", TRIP).query(String.class).list())
                .containsExactly("1:A1X", "2:B1", "4:C1", "5:B3");
        assertThat(jdbc.sql("SELECT MIN(capacity) || '-' || MAX(capacity) FROM standing_usage WHERE trip_id = :t AND coach_id = 20").param("t", TRIP)
                .query(String.class).single()).isEqualTo("12-12");
        assertThat(jdbc.sql("SELECT amount FROM trip_fare WHERE trip_id = :t AND segment_index = 0 AND class = 'FIRST'").param("t", TRIP)
                .query(BigDecimal.class).single()).isEqualByComparingTo("95.50");
    }

    @Test
    void aCoachThatLosesItsStandingCapacity_losesItsCounters_whenNoneIsInUse() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());

        push(TRIP, withCoaches(valid(), replace(valid().coaches(), 2, c -> new TripSnapshot.Coach(30L, "C", CoachClass.THIRD, 3, 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.standingCounters").value(3));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM standing_usage WHERE trip_id = :t AND coach_id = 30").param("t", TRIP).query(Integer.class).single()).isZero();
    }

    @Test
    void aTripWithoutReservations_canHaveItsTimesAndNamesChanged() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        List<TripSnapshot.Stop> later = replace(valid().stops(), 1, s -> new TripSnapshot.Stop(1, s.stationId(), s.stationCode(), "Giza (renamed)",
                s.arrivalTime().plusMinutes(10), s.departureTime().plusMinutes(10)));

        push(TRIP, withStops(valid(), later)).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT station_name FROM trip_stop WHERE trip_id = :t AND stop_index = 1").param("t", TRIP).query(String.class).single())
                .isEqualTo("Giza (renamed)");
    }

    // ---- conflicts with holds and bookings (FR-A9) ----------------------------------------------------------------------

    @Test
    void removingASeatThatHasReservations_isRefused_andNothingElseOfThePushIsApplied() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        book(TRIP, 3, 1);
        TripSnapshot withoutSeat3 = withSeats(valid(), without(valid().seats(), 2));
        TripSnapshot alsoRenamed = new TripSnapshot(withoutSeat3.departureDate(), withoutSeat3.train(), "Renamed route", withoutSeat3.stops(),
                withoutSeat3.coaches(), withoutSeat3.seats(), withoutSeat3.fares());

        push(TRIP, alsoRenamed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("capacity_conflict"))
                .andExpect(jsonPath("$.error.details[0].message").value(org.hamcrest.Matchers.containsString("B2")));

        assertThat(count("trip_seat")).isEqualTo(4);
        assertThat(routeName()).isEqualTo("Cairo-Alexandria");
    }

    @Test
    void loweringTheStandingCapacityBelowThePlacesInUse_isRefused_butToExactlyThePlacesInUseIsFine() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        useStanding(TRIP, 30, 1, 4); // coach 30: 4 of 5 places used on segment 1

        push(TRIP, withCoaches(valid(), replace(valid().coaches(), 2, c -> new TripSnapshot.Coach(30L, "C", CoachClass.THIRD, 3, 3))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("capacity_conflict"));
        push(TRIP, withCoaches(valid(), replace(valid().coaches(), 2, c -> new TripSnapshot.Coach(30L, "C", CoachClass.THIRD, 3, 4))))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT MAX(capacity) FROM standing_usage WHERE trip_id = :t AND coach_id = 30").param("t", TRIP).query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void removingACoachWhoseStandingPlacesAreInUse_isRefused() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        useStanding(TRIP, 30, 0, 1);
        TripSnapshot withoutThird = withSeats(withCoaches(valid(), without(valid().coaches(), 2)), without(valid().seats(), 3));

        push(TRIP, withoutThird).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("capacity_conflict"));

        assertThat(count("trip_coach")).isEqualTo(3);
        assertThat(count("standing_usage")).isEqualTo(6);
    }

    @Test
    void theClassOfASeatThatHasReservations_cannotChange_otherwiseTheBookedPriceAndTheCountsPerClassDisagree() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        book(TRIP, 2, 0); // seat 2 is SECOND class in coach 20
        TripSnapshot upgraded = withCoaches(valid(), replace(valid().coaches(), 1, c -> new TripSnapshot.Coach(20L, "B", CoachClass.FIRST, 2, 10)));
        upgraded = withSeats(upgraded, replace(replace(valid().seats(), 1, s -> new TripSnapshot.Seat(2L, 20L, "B1", 1, "A", CoachClass.FIRST)),
                2, s -> new TripSnapshot.Seat(3L, 20L, "B2", 1, "B", CoachClass.FIRST)));

        push(TRIP, upgraded).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("capacity_conflict"))
                .andExpect(jsonPath("$.error.details[0].message").value(org.hamcrest.Matchers.containsString("class")));

        assertThat(jdbc.sql("SELECT class FROM trip_seat WHERE trip_id = :t AND seat_id = 2").param("t", TRIP).query(String.class).single()).isEqualTo("SECOND");
    }

    @Test
    void theClassOfACoachWhoseStandingPlacesAreInUse_cannotChange() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        useStanding(TRIP, 30, 0, 1);
        TripSnapshot changed = withSeats(withCoaches(valid(), replace(valid().coaches(), 2, c -> new TripSnapshot.Coach(30L, "C", CoachClass.SECOND, 3, 5))),
                replace(valid().seats(), 3, s -> new TripSnapshot.Seat(4L, 30L, "C1", 1, "A", CoachClass.SECOND)));

        push(TRIP, changed).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("capacity_conflict"));
    }

    @Test
    void theClassOfACoachWithoutReservations_canChange() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        TripSnapshot upgraded = withCoaches(valid(), replace(valid().coaches(), 1, c -> new TripSnapshot.Coach(20L, "B", CoachClass.FIRST, 2, 10)));
        upgraded = withSeats(upgraded, replace(replace(valid().seats(), 1, s -> new TripSnapshot.Seat(2L, 20L, "B1", 1, "A", CoachClass.FIRST)),
                2, s -> new TripSnapshot.Seat(3L, 20L, "B2", 1, "B", CoachClass.FIRST)));

        push(TRIP, upgraded).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT class FROM trip_seat WHERE trip_id = :t AND seat_id = 2").param("t", TRIP).query(String.class).single()).isEqualTo("FIRST");
    }

    @Test
    void aTripWithReservations_keepsItsStopsTimesTrainDateAndFares() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        book(TRIP, 1, 0);

        TripSnapshot newFare = withFares(valid(), replace(valid().fares(), 0, f -> new TripSnapshot.Fare(0, CoachClass.FIRST, new BigDecimal("81.00"), "EGP")));
        TripSnapshot newTime = withStops(valid(), replace(valid().stops(), 0, s -> new TripSnapshot.Stop(0, s.stationId(), s.stationCode(), s.stationName(), null, s.departureTime().plusHours(1))));
        TripSnapshot newDate = withDate(valid(), valid().departureDate().plusDays(1));

        for (TripSnapshot changed : List.of(newFare, newTime, newDate)) {
            push(TRIP, changed).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("trip_has_reservations"));
        }
        // The same snapshot again, and a change that only adds capacity, are fine.
        push(TRIP, valid()).andExpect(status().isOk());
        push(TRIP, withSeats(valid(), java.util.stream.Stream.concat(valid().seats().stream(),
                java.util.stream.Stream.of(new TripSnapshot.Seat(9L, 10L, "A2", 1, "B", CoachClass.FIRST))).toList()))
                .andExpect(status().isOk());
        assertThat(count("trip_seat")).isEqualTo(5);
    }

    @Test
    void aTripThatIsNotOpen_acceptsNoSnapshot() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        jdbc.sql("UPDATE trip SET status = 'CANCELLING' WHERE id = :t").param("t", TRIP).update();

        push(TRIP, valid()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("trip_not_open"));
        assertThat(jdbc.sql("SELECT status FROM trip WHERE id = :t").param("t", TRIP).query(String.class).single()).isEqualTo("CANCELLING"); // never reset by a push
    }

    // ---- all or nothing ------------------------------------------------------------------------------------------------

    @Test
    void ifAnyStepFails_theWholePushIsRolledBack() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        // Fares are written last. Make that step fail after the trip, stops, coaches and seats were already changed.
        doThrow(new IllegalStateException("simulated failure while writing fares")).when(repository).replaceFares(anyLong(), any());
        TripSnapshot changed = new TripSnapshot(valid().departureDate(), valid().train(), "Changed route", valid().stops(),
                valid().coaches(), withSeats(valid(), without(valid().seats(), 3)).seats(), valid().fares());

        assertThatThrownBy(() -> service.apply(TRIP, changed, "admin-sub")).isInstanceOf(IllegalStateException.class);

        assertThat(routeName()).isEqualTo("Cairo-Alexandria"); // the earlier steps were rolled back
        assertThat(count("trip_seat")).isEqualTo(4);
    }

    @Test
    void aSnapshotThatFailsValidation_storesNothing() throws Exception {
        TripSnapshot orphan = withSeats(valid(), replace(valid().seats(), 0, s -> new TripSnapshot.Seat(1L, 99L, "A1", 1, "A", CoachClass.FIRST)));

        push(TRIP, orphan).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("seats[0].coachId"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM trip").query(Integer.class).single()).isZero();
    }

    // ---- delete ----------------------------------------------------------------------------------------------------------

    @Test
    void deletingATrip_removesTheWholeCopy() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());

        mvc.perform(delete("/internal/trips/{id}", TRIP).header("X-Internal-Api-Key", INTERNAL_KEY).header("X-Acting-User", "admin-sub"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM trip").query(Integer.class).single()).isZero();
        assertThat(count("trip_seat") + count("trip_stop") + count("standing_usage") + count("trip_fare")).isZero();
        assertThat(jdbc.sql("SELECT action FROM audit_log ORDER BY id").query(String.class).list()).containsExactly("CREATE", "DELETE");
    }

    @Test
    void deletingATripThatHasReservations_isRefused() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        hold(TRIP, 2, 1, 5);

        mvc.perform(delete("/internal/trips/{id}", TRIP).header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("trip_has_reservations"));

        assertThat(count("trip_seat")).isEqualTo(4);
    }

    @Test
    void deletingATripWithStandingPlacesInUse_isRefused() throws Exception {
        push(TRIP, valid()).andExpect(status().isOk());
        useStanding(TRIP, 20, 0, 1);

        mvc.perform(delete("/internal/trips/{id}", TRIP).header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("trip_has_reservations"));
    }

    @Test
    void deletingATripThatBookingNeverHad_isAccepted() throws Exception {
        mvc.perform(delete("/internal/trips/{id}", 999999).header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isNoContent());
    }
}
