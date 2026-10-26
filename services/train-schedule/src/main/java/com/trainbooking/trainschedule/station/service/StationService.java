package com.trainbooking.trainschedule.station.service;

import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.station.model.Station;
import com.trainbooking.trainschedule.station.model.StationCodeTakenException;
import com.trainbooking.trainschedule.station.model.StationInUseException;
import com.trainbooking.trainschedule.station.model.StationNotFoundException;
import com.trainbooking.trainschedule.station.model.StationRequest;
import com.trainbooking.trainschedule.station.repository.StationRepository;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Station rules. Every change and its audit row are written in one transaction: both happen or neither does. */
@Service
public class StationService {

    private static final String ENTITY = "station";

    private final StationRepository stations;
    private final AuditTrail audit;

    public StationService(StationRepository stations, AuditTrail audit) {
        this.stations = stations;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Station> list() {
        return stations.findAll();
    }

    @Transactional(readOnly = true)
    public Station get(long id) {
        return stations.findById(id).orElseThrow(() -> new StationNotFoundException(id));
    }

    @Transactional
    public Station create(StationRequest request, String adminId) {
        String code = normalizeCode(request.code());
        Station created;
        try {
            created = stations.insert(code, request.name().strip(), request.city().strip());
        } catch (DuplicateKeyException e) {
            // The unique constraint on code (ORA-00001) is the real guard; checking first would race with another admin.
            throw new StationCodeTakenException(code);
        }
        audit.change(adminId, ENTITY, created.id(), "CREATE", null, created);
        return created;
    }

    @Transactional
    public Station update(long id, StationRequest request, String adminId) {
        Station before = get(id);
        Station after = new Station(id, normalizeCode(request.code()), request.name().strip(), request.city().strip());
        try {
            if (!stations.update(id, after.code(), after.name(), after.city())) {
                throw new StationNotFoundException(id); // deleted by another admin between the read and the update
            }
        } catch (DuplicateKeyException e) {
            throw new StationCodeTakenException(after.code());
        }
        audit.change(adminId, ENTITY, id, "UPDATE", before, after);
        return after;
    }

    /** The station with this code (any case), if there is one. Used by trip search. */
    @Transactional(readOnly = true)
    public java.util.Optional<Station> findByCode(String code) {
        return stations.findByCode(code);
    }

    /** The ids from the given ones that exist (used to validate the stops of a route). */
    @Transactional(readOnly = true)
    public Set<Long> existingIds(Collection<Long> ids) {
        return stations.findExistingIds(ids);
    }

    @Transactional
    public void delete(long id, String adminId) {
        Station before = get(id);
        try {
            if (!stations.delete(id)) {
                throw new StationNotFoundException(id);
            }
        } catch (DataIntegrityViolationException e) {
            // ORA-02292: a route_stop row still points at this station. The foreign key is the real guard.
            String message = String.valueOf(e.getMessage()).toUpperCase(Locale.ROOT);
            if (message.contains("ORA-02292") || message.contains("FK_ROUTE_STOP_STATION")) {
                throw new StationInUseException(id);
            }
            throw e;
        }
        audit.change(adminId, ENTITY, id, "DELETE", before, null);
    }

    private static String normalizeCode(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }
}
