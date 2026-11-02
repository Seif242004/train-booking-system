package com.trainbooking.bookingseat.testsupport;

import com.trainbooking.bookingseat.common.RemoteServiceException;
import com.trainbooking.bookingseat.notification.NotificationPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stands in for the Notification service: stores one email per dedupe key, like the real one, and can be switched off. */
public class FakeNotificationPort implements NotificationPort {

    private final List<Email> accepted = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> keys = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean down;

    public void reset() {
        accepted.clear();
        keys.clear();
        down = false;
    }

    public void down(boolean down) {
        this.down = down;
    }

    /** The emails stored (a repeated dedupe key is stored once). */
    public List<Email> accepted() {
        return List.copyOf(accepted);
    }

    @Override
    public void send(Email email) {
        if (down) {
            throw new RemoteServiceException("Notification is down (test)");
        }
        if (keys.add(email.dedupeKey())) {
            accepted.add(email);
        }
    }
}
