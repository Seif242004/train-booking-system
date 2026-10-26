package com.trainbooking.trainschedule.station;

import com.trainbooking.trainschedule.audit.AuditLogRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Station rules. Every change and its audit row are written in one transaction: both happen or neither does. */
@Service
public class StationService {

    private static final String ENTITY = "station";

    private final StationRepository stations;
    private final AuditLogRepository audit;
    private final JsonMapper jsonMapper;

    public StationService(StationRepository stations, AuditLogRepository audit, JsonMapper jsonMapper) {
        this.stations = stations;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
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
        audit.record(adminId, ENTITY, created.id(), "CREATE", diff(null, created));
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
        audit.record(adminId, ENTITY, id, "UPDATE", diff(before, after));
        return after;
    }

    @Transactional
    public void delete(long id, String adminId) {
        Station before = get(id);
        if (!stations.delete(id)) {
            throw new StationNotFoundException(id);
        }
        audit.record(adminId, ENTITY, id, "DELETE", diff(before, null));
    }

    private static String normalizeCode(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }

    private String diff(Station before, Station after) {
        Map<String, Object> diff = new java.util.LinkedHashMap<>();
        diff.put("before", before);
        diff.put("after", after);
        return jsonMapper.writeValueAsString(diff);
    }
}
