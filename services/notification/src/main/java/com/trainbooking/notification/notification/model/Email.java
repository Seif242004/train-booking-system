package com.trainbooking.notification.notification.model;

import java.time.OffsetDateTime;

/**
 * One email, as stored. {@code attachment} is null when there is none; the list queries leave it (and the bodies) out and
 * {@code EmailRepository.find} loads the whole row.
 */
public record Email(
        long id,
        String dedupeKey,
        NotificationRequest.Kind kind,
        String reference,
        String recipient,
        String recipientName,
        String subject,
        String bodyText,
        String bodyHtml,
        String attachmentName,
        String attachmentType,
        byte[] attachment,
        EmailStatus status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        String lastError,
        OffsetDateTime sentAt) {
}
