package com.trainbooking.bookingseat.testsupport;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.model.PickRequest;
import com.trainbooking.bookingseat.booking.service.HoldService;

/**
 * Makes a whole selection the way the seat map does: one pick after the other. The first pick starts the hold, the others join it,
 * and a pick that fails stops the series (the picks before it stay held, as in the real flow).
 */
public class Holds {

    private final HoldService service;

    public Holds(HoldService service) {
        this.service = service;
    }

    /** @return the hold after the last pick */
    public BookingView hold(AuthenticatedUser user, HoldRequest selection) {
        BookingView booking = null;
        for (long seatId : selection.seats()) {
            booking = service.pick(user, new PickRequest(selection.tripId(), selection.fromStop(), selection.toStop(), seatId, null, null)).booking();
        }
        for (HoldRequest.StandingRequest places : selection.standingPlaces()) {
            booking = service.pick(user, new PickRequest(selection.tripId(), selection.fromStop(), selection.toStop(), null, places.coachId(), places.qty())).booking();
        }
        return booking;
    }

    public HoldService service() {
        return service;
    }
}
