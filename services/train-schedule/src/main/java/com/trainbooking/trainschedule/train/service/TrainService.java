package com.trainbooking.trainschedule.train.service;

import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.common.SnapshotChanges;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.model.TrainInUseException;
import com.trainbooking.trainschedule.train.model.TrainNotFoundException;
import com.trainbooking.trainschedule.train.model.TrainNumberTakenException;
import com.trainbooking.trainschedule.train.model.TrainRequest;
import com.trainbooking.trainschedule.train.model.TrainStatus;
import com.trainbooking.trainschedule.train.repository.TrainRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Train rules (FR-A1 to FR-A4). Every change and its audit row are written in one transaction. */
@Service
public class TrainService {

    private static final String ENTITY = "train";

    private final TrainRepository trains;
    private final AuditTrail audit;
    private final SnapshotChanges changes;

    public TrainService(TrainRepository trains, AuditTrail audit, SnapshotChanges changes) {
        this.trains = trains;
        this.audit = audit;
        this.changes = changes;
    }

    /** @param status optional filter, ACTIVE or INACTIVE; anything else is a 400 */
    @Transactional(readOnly = true)
    public List<Train> list(String number, String name, String status) {
        return trains.search(blankToNull(number), blankToNull(name), parseStatusFilter(status));
    }

    @Transactional(readOnly = true)
    public Train get(long id) {
        return trains.findById(id).orElseThrow(() -> new TrainNotFoundException(id));
    }

    @Transactional
    public Train create(TrainRequest request, String adminId) {
        String number = normalizeNumber(request.number());
        Train created;
        try {
            created = trains.insert(number, request.name().strip(), request.type().strip(), TrainStatus.valueOf(request.status()));
        } catch (DuplicateKeyException e) {
            // The unique constraint (ORA-00001) is the real guard; checking first would race with another admin.
            throw new TrainNumberTakenException(number);
        }
        audit.change(adminId, ENTITY, created.id(), "CREATE", null, created);
        return created;
    }

    @Transactional
    public Train update(long id, TrainRequest request, String adminId) {
        Train before = get(id);
        Train after = new Train(id, normalizeNumber(request.number()), request.name().strip(), request.type().strip(),
                TrainStatus.valueOf(request.status()));
        try {
            if (!trains.update(id, after.number(), after.name(), after.type(), after.status())) {
                throw new TrainNotFoundException(id); // deleted by another admin between the read and the update
            }
        } catch (DuplicateKeyException e) {
            throw new TrainNumberTakenException(after.number());
        }
        audit.change(adminId, ENTITY, id, "UPDATE", before, after);
        changes.trainChanged(id, adminId);
        return after;
    }

    /**
     * Deletes a train together with its coaches and seats (database cascade). A train that has any trips cannot be
     * deleted (409 train_in_use): the foreign key from trip to train is the guard. It can be set to INACTIVE instead.
     * This is deliberately stricter than FR-A4, which only blocks the delete when future trips have confirmed bookings: a
     * train with any trip, even one without bookings, is kept, so a trip never loses its train and Booking and Seat never
     * keeps a copy of a trip whose train is gone. To remove such a train the admin deletes or cancels its trips first (a
     * trip with holds or bookings is cancelled, Flow 8) or sets the train to INACTIVE.
     */
    @Transactional
    public void delete(long id, String adminId) {
        Train before = get(id);
        // Count first: after the delete the cascaded rows are gone. The counts go into the audit row.
        int coaches = trains.countCoaches(id);
        int seats = trains.countSeats(id);
        try {
            if (!trains.delete(id)) {
                throw new TrainNotFoundException(id);
            }
        } catch (DataIntegrityViolationException e) {
            // ORA-02292: a trip still points at this train. The foreign key is the real guard.
            String message = String.valueOf(e.getMessage()).toUpperCase(Locale.ROOT);
            if (message.contains("ORA-02292") || message.contains("FK_TRIP_TRAIN")) {
                throw new TrainInUseException(id);
            }
            throw e;
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("before", before);
        diff.put("after", null);
        diff.put("cascadedCoaches", coaches);
        diff.put("cascadedSeats", seats);
        audit.record(adminId, ENTITY, id, "DELETE", diff);
    }

    private static String normalizeNumber(String number) {
        return number.strip().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static TrainStatus parseStatusFilter(String status) {
        String value = blankToNull(status);
        if (value == null) {
            return null;
        }
        try {
            return TrainStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RequestValidationException(List.of(new ApiError.Detail("status", "must be ACTIVE or INACTIVE")));
        }
    }
}
