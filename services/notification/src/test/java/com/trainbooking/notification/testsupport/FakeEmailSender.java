package com.trainbooking.notification.testsupport;

import com.trainbooking.notification.mail.EmailSender;
import com.trainbooking.notification.notification.model.Email;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** Stands in for the mail server: records what it was given, and can be switched off or made to refuse some addresses. */
public class FakeEmailSender implements EmailSender {

    private final List<Email> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> attempts = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean down;
    private volatile Predicate<Email> refuses = email -> false;

    public void reset() {
        sent.clear();
        attempts.clear();
        down = false;
        refuses = email -> false;
    }

    public void down(boolean down) {
        this.down = down;
    }

    public void refuses(Predicate<Email> refuses) {
        this.refuses = refuses;
    }

    public List<Email> sent() {
        return List.copyOf(sent);
    }

    /** Every call, successful or not (the id of the email). */
    public int attempts() {
        return attempts.size();
    }

    @Override
    public void send(Email email) {
        attempts.add((int) email.id());
        if (down) {
            throw new IllegalStateException("Mail server is down (test)");
        }
        if (refuses.test(email)) {
            throw new IllegalStateException("550 mailbox unavailable (test)");
        }
        sent.add(email);
    }
}
