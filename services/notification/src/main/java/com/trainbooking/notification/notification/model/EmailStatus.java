package com.trainbooking.notification.notification.model;

/**
 * Life cycle of a stored email: PENDING (waiting to be sent or retried), SENT (the mail server took it), FAILED (given up after
 * the maximum number of attempts). Matches the CHECK constraint ck_email_status.
 */
public enum EmailStatus {
    PENDING, SENT, FAILED
}
