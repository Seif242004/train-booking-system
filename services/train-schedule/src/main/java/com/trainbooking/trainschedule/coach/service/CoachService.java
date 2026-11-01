package com.trainbooking.trainschedule.coach.service;

import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.common.SnapshotChanges;
import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.coach.model.CoachCodeTakenException;
import com.trainbooking.trainschedule.coach.model.CoachNotFoundException;
import com.trainbooking.trainschedule.coach.model.CoachPositionTakenException;
import com.trainbooking.trainschedule.coach.model.CoachRequest;
import com.trainbooking.trainschedule.coach.repository.CoachRepository;
import com.trainbooking.trainschedule.seat.repository.SeatRepository;
import com.trainbooking.trainschedule.train.service.TrainService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coach rules (FR-A5, FR-A7). Every change and its audit row are written in one transaction. */
@Service
public class CoachService {

    private static final String ENTITY = "coach";

    private final CoachRepository coaches;
    private final SeatRepository seats;
    private final TrainService trains;
    private final AuditTrail audit;
    private final SnapshotChanges changes;

    public CoachService(CoachRepository coaches, SeatRepository seats, TrainService trains, AuditTrail audit, SnapshotChanges changes) {
        this.coaches = coaches;
        this.seats = seats;
        this.trains = trains;
        this.audit = audit;
        this.changes = changes;
    }

    /** The coaches of a train in their order in the train. 404 if the train does not exist. */
    @Transactional(readOnly = true)
    public List<Coach> listByTrain(long trainId) {
        trains.get(trainId);
        return coaches.findByTrain(trainId);
    }

    @Transactional(readOnly = true)
    public Coach get(long id) {
        return coaches.findById(id).orElseThrow(() -> new CoachNotFoundException(id));
    }

    @Transactional
    public Coach create(long trainId, CoachRequest request, String adminId) {
        trains.get(trainId); // 404 if the train does not exist
        String code = normalizeCode(request.code());
        CoachClass coachClass = CoachClass.valueOf(request.coachClass());
        Coach created;
        try {
            created = coaches.insert(trainId, code, coachClass, request.position(), request.standingCapacity());
        } catch (DuplicateKeyException e) {
            throw taken(e, code, request.position());
        }
        audit.change(adminId, ENTITY, created.id(), "CREATE", null, created);
        changes.trainChanged(trainId, adminId);
        return created;
    }

    /**
     * Updates a coach. If its class changes, the class of all its seats is updated in the same transaction, because a
     * seat always has the class of its coach (the server derives it; a client never sets it).
     * The snapshots of the future trips of the train are pushed again; Booking and Seat refuses the change (409) if it
     * conflicts with holds or bookings, for example a standing capacity below the places in use (FR-A9).
     */
    @Transactional
    public Coach update(long id, CoachRequest request, String adminId) {
        Coach before = get(id);
        Coach after = new Coach(id, before.trainId(), normalizeCode(request.code()), CoachClass.valueOf(request.coachClass()),
                request.position(), request.standingCapacity());
        try {
            if (!coaches.update(id, after.code(), after.coachClass(), after.position(), after.standingCapacity())) {
                throw new CoachNotFoundException(id); // deleted by another admin between the read and the update
            }
        } catch (DuplicateKeyException e) {
            throw taken(e, after.code(), after.position());
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("before", before);
        diff.put("after", after);
        if (before.coachClass() != after.coachClass()) {
            diff.put("seatsReclassified", seats.updateClassByCoach(id, after.coachClass()));
        }
        audit.record(adminId, ENTITY, id, "UPDATE", diff);
        changes.trainChanged(after.trainId(), adminId);
        return after;
    }

    /**
     * Deletes a coach together with its seats (database cascade).
     * The snapshots of the future trips of the train are pushed again; Booking and Seat refuses the delete (409) if seats
     * or standing places of the coach have holds or bookings (FR-A9).
     */
    @Transactional
    public void delete(long id, String adminId) {
        Coach before = get(id);
        int seatCount = seats.countByCoach(id); // counted first: the cascade removes the rows
        if (!coaches.delete(id)) {
            throw new CoachNotFoundException(id);
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("before", before);
        diff.put("after", null);
        diff.put("cascadedSeats", seatCount);
        audit.record(adminId, ENTITY, id, "DELETE", diff);
        changes.trainChanged(before.trainId(), adminId);
    }

    private static String normalizeCode(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }

    /** Picks the right error from the name of the violated unique constraint. Anything unexpected is rethrown. */
    private static RuntimeException taken(DuplicateKeyException e, String code, int position) {
        String message = String.valueOf(e.getMessage()).toUpperCase(Locale.ROOT);
        if (message.contains("UK_COACH_POSITION")) {
            return new CoachPositionTakenException(position);
        }
        if (message.contains("UK_COACH_CODE")) {
            return new CoachCodeTakenException(code);
        }
        return e;
    }
}
