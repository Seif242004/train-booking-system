package com.trainbooking.trainschedule.station.controller;

import com.trainbooking.trainschedule.station.model.Station;
import com.trainbooking.trainschedule.station.service.StationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only list of stations for any signed-in user, so that the passenger search page can offer the stations to choose
 * from. Changing stations stays admin-only under /admin/stations.
 */
@RestController
public class StationListController {

    private final StationService service;

    public StationListController(StationService service) {
        this.service = service;
    }

    @GetMapping("/stations")
    public List<Station> list() {
        return service.list();
    }
}
