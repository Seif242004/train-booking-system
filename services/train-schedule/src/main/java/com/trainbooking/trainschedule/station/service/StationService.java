package com.trainbooking.trainschedule.station.service;

import com.trainbooking.trainschedule.audit.AuditTrail;
import com.trainbooking.trainschedule.station.model.Station;
import com.trainbooking.trainschedule.station.model.StationCodeTakenException;
import com.trainbooking.trainschedule.station.model.StationNotFoundException;
import com.trainbooking.trainschedule.station.model.StationRequest;
import com.trainbooking.trainschedule.station.repository.StationRepository;
import java.util.List;
import java.util.Locale;
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

    @Transactional
    public void delete(long id, String adminId) {
        Station before = get(id);
        if (!stations.delete(id)) {
            throw new StationNotFoundException(id);
        }
        audit.change(adminId, ENTITY, id, "DELETE", before, null);
    }

    private static String normalizeCode(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }
}
