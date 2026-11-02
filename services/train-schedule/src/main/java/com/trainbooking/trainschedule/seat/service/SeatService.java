package com.trainbooking.trainschedule.seat.service;

import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.common.SnapshotChanges;
import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.service.CoachService;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.ListValidation;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.seat.model.Seat;
import com.trainbooking.trainschedule.seat.model.SeatConflictException;
import com.trainbooking.trainschedule.seat.model.SeatNotFoundException;
import com.trainbooking.trainschedule.seat.model.SeatRequest;
import com.trainbooking.trainschedule.seat.repository.SeatRepository;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Seat rules (FR-A6). Every change and its audit row are written in one transaction. */
@Service
public class SeatService {

    private static final String ENTITY = "seat";
    /** A single request may add at most this many seats; a coach is far smaller than this. */
    static final int MAX_SEATS_PER_REQUEST = 200;

    private final SeatRepository seats;
    private final CoachService coaches;
    private final Validator validator;
    private final AuditTrail audit;
    private final SnapshotChanges changes;

    public SeatService(SeatRepository seats, CoachService coaches, Validator validator, AuditTrail audit, SnapshotChanges changes) {
        this.seats = seats;
        this.coaches = coaches;
        this.validator = validator;
        this.audit = audit;
        this.changes = changes;
    }

    /** The seats of a coach in seat-map order. 404 if the coach does not exist. */
    @Transactional(readOnly = true)
    public List<Seat> listByCoach(long coachId) {
        coaches.get(coachId);
        return seats.findByCoach(coachId);
    }

    /**
     * Adds a whole list of seats to a coach, all or nothing. Any problem rejects the entire request and nothing is
     * saved: an invalid seat, a seat number or a row and column position repeated inside the list (400), or one that
     * already exists in the coach (409). The class of every new seat is the coach's class.
     */
    @Transactional
    public List<Seat> addSeats(long coachId, List<SeatRequest> requests, String adminId) {
        Coach coach = coaches.get(coachId); // 404 if the coach does not exist

        // The list as a whole and every seat in it, reporting every problem at once (seats are saved all or nothing).
        ListValidation.require(validator, requests, "seats", MAX_SEATS_PER_REQUEST);
        List<SeatRequest> clean = requests.stream().map(SeatService::normalize).toList();
        rejectDuplicatesInsideTheList(clean);

        // Report clashes with the coach's existing seats up front, with the seat that clashes. The unique constraints
        // below stay as the final guard, for example when another admin adds seats at the same moment.
        List<Seat> existing = seats.findByCoach(coachId);
        rejectClashesWithExistingSeats(clean, existing);

        List<Seat> toInsert = clean.stream()
                .map(r -> new Seat(0, coachId, r.seatNumber(), r.row(), r.column(), coach.coachClass()))
                .toList();
        try {
            seats.insertAll(coachId, coach.coachClass(), toInsert);
        } catch (DuplicateKeyException e) {
            throw new SeatConflictException(List.of(new ApiError.Detail("seats",
                    "A seat number or position was taken by another change at the same time. Nothing was saved.")));
        }

        Set<String> addedNumbers = new HashSet<>();
        clean.forEach(r -> addedNumbers.add(r.seatNumber()));
        List<Seat> created = seats.findByCoach(coachId).stream().filter(s -> addedNumbers.contains(s.seatNumber())).toList();

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("count", created.size());
        diff.put("seatNumbers", created.stream().map(Seat::seatNumber).toList());
        audit.record(adminId, "coach", coachId, "ADD_SEATS", diff);
        changes.trainChanged(coach.trainId(), adminId);
        return created;
    }

    @Transactional
    public Seat update(long id, SeatRequest request, String adminId) {
        Seat before = seats.findById(id).orElseThrow(() -> new SeatNotFoundException(id));
        SeatRequest clean = normalize(request);
        try {
            if (!seats.update(id, clean.seatNumber(), clean.row(), clean.column())) {
                throw new SeatNotFoundException(id); // deleted by another admin between the read and the update
            }
        } catch (DuplicateKeyException e) {
            String message = String.valueOf(e.getMessage()).toUpperCase(Locale.ROOT);
            if (message.contains("UK_SEAT_NUMBER")) {
                throw new SeatConflictException(List.of(new ApiError.Detail("seatNumber", "already exists in this coach")));
            }
            if (message.contains("UK_SEAT_POSITION")) {
                throw new SeatConflictException(List.of(new ApiError.Detail("column", "this row and column already has a seat")));
            }
            throw e;
        }
        Seat after = new Seat(id, before.coachId(), clean.seatNumber(), clean.row(), clean.column(), before.coachClass());
        audit.change(adminId, ENTITY, id, "UPDATE", before, after);
        changes.trainChanged(coaches.get(before.coachId()).trainId(), adminId);
        return after;
    }

    @Transactional
    public void delete(long id, String adminId) {
        Seat before = seats.findById(id).orElseThrow(() -> new SeatNotFoundException(id));
        // FR-A9: removing a seat that has holds or confirmed bookings on a future trip is refused by Booking & Seat when the changed
        // trips are pushed to it (trainChanged below, before the commit), which rolls this delete back.
        if (!seats.delete(id)) {
            throw new SeatNotFoundException(id);
        }
        audit.change(adminId, ENTITY, id, "DELETE", before, null);
        changes.trainChanged(coaches.get(before.coachId()).trainId(), adminId);
    }

    /** A seat number, or a row and column position, may appear only once in the list. */
    private static void rejectDuplicatesInsideTheList(List<SeatRequest> requests) {
        List<ApiError.Detail> problems = new ArrayList<>();
        Map<String, Integer> firstByNumber = new HashMap<>();
        Map<String, Integer> firstByPosition = new HashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            SeatRequest r = requests.get(i);
            Integer sameNumber = firstByNumber.putIfAbsent(r.seatNumber(), i);
            if (sameNumber != null) {
                problems.add(new ApiError.Detail("[" + i + "].seatNumber",
                        "seat number " + r.seatNumber() + " is repeated (also at position " + sameNumber + " in the list)"));
            }
            Integer samePosition = firstByPosition.putIfAbsent(r.row() + "/" + r.column(), i);
            if (samePosition != null) {
                problems.add(new ApiError.Detail("[" + i + "].column",
                        "row " + r.row() + " column " + r.column() + " is repeated (also at position " + samePosition + " in the list)"));
            }
        }
        if (!problems.isEmpty()) {
            throw new RequestValidationException(problems);
        }
    }

    private static void rejectClashesWithExistingSeats(List<SeatRequest> requests, List<Seat> existing) {
        Set<String> numbers = new HashSet<>();
        Set<String> positions = new HashSet<>();
        for (Seat seat : existing) {
            numbers.add(seat.seatNumber());
            positions.add(seat.row() + "/" + seat.column());
        }
        List<ApiError.Detail> problems = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            SeatRequest r = requests.get(i);
            if (numbers.contains(r.seatNumber())) {
                problems.add(new ApiError.Detail("[" + i + "].seatNumber", "seat " + r.seatNumber() + " already exists in this coach"));
            }
            if (positions.contains(r.row() + "/" + r.column())) {
                problems.add(new ApiError.Detail("[" + i + "].column",
                        "row " + r.row() + " column " + r.column() + " already has a seat in this coach"));
            }
        }
        if (!problems.isEmpty()) {
            throw new SeatConflictException(problems);
        }
    }

    private static SeatRequest normalize(SeatRequest request) {
        return new SeatRequest(request.seatNumber().strip().toUpperCase(Locale.ROOT), request.row(),
                request.column().strip().toUpperCase(Locale.ROOT));
    }
}
