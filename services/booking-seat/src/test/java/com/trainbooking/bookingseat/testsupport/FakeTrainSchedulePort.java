package com.trainbooking.bookingseat.testsupport;

import com.trainbooking.bookingseat.common.RemoteServiceException;
import com.trainbooking.bookingseat.schedule.TrainSchedulePort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stands in for Train & Schedule: remembers the trips it was told are cancelled, and can be switched off. */
public class FakeTrainSchedulePort implements TrainSchedulePort {

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean down;

    public void reset() {
        calls.clear();
        down = false;
    }

    public void down(boolean down) {
        this.down = down;
    }

    /** One entry per accepted call: "tripId:admin". */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public void markTripCancelled(long tripId, String adminId) {
        if (down) {
            throw new RemoteServiceException("Train & Schedule is down (test)");
        }
        calls.add(tripId + ":" + adminId);
    }
}
