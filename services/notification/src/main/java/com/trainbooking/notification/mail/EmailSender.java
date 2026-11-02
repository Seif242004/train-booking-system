package com.trainbooking.notification.mail;

import com.trainbooking.notification.notification.model.Email;

/** Sends one email by SMTP. An interface so that the retry logic can be tested without a mail server. */
public interface EmailSender {

    /** @throws RuntimeException if the email could not be handed to the mail server: the caller tries again later */
    void send(Email email);
}
