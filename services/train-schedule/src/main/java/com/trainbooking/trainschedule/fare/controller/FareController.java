package com.trainbooking.trainschedule.fare.controller;

import com.trainbooking.trainschedule.fare.model.Fare;
import com.trainbooking.trainschedule.fare.model.FareRequest;
import com.trainbooking.trainschedule.fare.service.FareService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Read and set the fares of a route (SRS 9.2). The ADMIN role is enforced in the security filter chain. */
@RestController
public class FareController {

    private final FareService service;

    public FareController(FareService service) {
        this.service = service;
    }

    @GetMapping("/admin/routes/{routeId}/fares")
    public List<Fare> list(@PathVariable long routeId) {
        return service.list(routeId);
    }

    /**
     * Sets all the fares of the route: the body is the complete list, saved all or nothing. It is validated by the
     * service (not by @Valid) so that every error names the position of the fare in the list.
     */
    @PutMapping("/admin/routes/{routeId}/fares")
    public List<Fare> replace(@PathVariable long routeId, @RequestBody List<FareRequest> request,
                              @AuthenticationPrincipal Jwt admin) {
        return service.replaceAll(routeId, request, admin.getSubject());
    }
}
