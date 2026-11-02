package com.trainbooking.bookingseat.testsupport;

import com.trainbooking.bookingseat.common.RemoteServiceException;
import com.trainbooking.bookingseat.ticket.TicketPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stands in for the Ticket service: remembers the tickets it was asked for, and can be switched off. */
public class FakeTicketPort implements TicketPort {

    private final List<Long> issueCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<TicketData> issued = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> invalidated = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean down;

    public void reset() {
        issueCalls.clear();
        issued.clear();
        invalidated.clear();
        down = false;
    }

    public void down(boolean down) {
        this.down = down;
    }

    public List<Long> issueCalls() {
        return List.copyOf(issueCalls);
    }

    public List<TicketData> issued() {
        return List.copyOf(issued);
    }

    public List<Long> invalidated() {
        return List.copyOf(invalidated);
    }

    @Override
    public void issue(long bookingId, TicketData data) {
        check();
        issueCalls.add(bookingId);
        if (!issueCalls.isEmpty() && issued.stream().noneMatch(t -> t.reference().equals(data.reference()))) {
            issued.add(data); // idempotent, like the real one: one ticket per booking
        }
    }

    @Override
    public byte[] pdf(long bookingId) {
        check();
        return ("%PDF-1.4 ticket of booking " + bookingId).getBytes();
    }

    @Override
    public void invalidate(long bookingId) {
        check();
        invalidated.add(bookingId);
    }

    private void check() {
        if (down) {
            throw new RemoteServiceException("Ticket is down (test)");
        }
    }
}
