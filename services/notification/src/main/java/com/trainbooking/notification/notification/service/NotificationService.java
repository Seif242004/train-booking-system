package com.trainbooking.notification.notification.service;

import com.trainbooking.notification.audit.AuditTrail;
import com.trainbooking.notification.notification.model.Email;
import com.trainbooking.notification.notification.model.NotificationRequest;
import com.trainbooking.notification.notification.repository.EmailRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes the request for an email and stores it, nothing more: the caller gets its answer at once, whether or not the mail server is up.
 * A poller sends the email afterwards ({@link EmailDispatcher}). So a mail server that is down, or an address that is refused, can
 * never fail the payment, the confirmation or the cancellation that asked for the email.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** The email, and whether this request stored it (false: the same dedupe key was stored by an earlier request). */
    public record Accepted(Email email, boolean created) {
    }

    private final EmailRepository emails;
    private final EmailTemplates templates;
    private final AuditTrail audit;
    private final Clock clock;

    public NotificationService(EmailRepository emails, EmailTemplates templates, AuditTrail audit, Clock clock) {
        this.emails = emails;
        this.templates = templates;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Accepted accept(NotificationRequest request) {
        Email existing = emails.findByDedupeKey(request.dedupeKey()).orElse(null);
        if (existing != null) {
            return new Accepted(existing, false);
        }
        EmailTemplates.Rendered rendered = templates.render(request);
        NotificationRequest.Attachment attachment = request.attachment();
        byte[] bytes = attachment == null ? null : Base64.getDecoder().decode(attachment.contentBase64());
        try {
            Email stored = emails.insert(request.dedupeKey(), request.kind(), request.reference(), request.to(), request.toName(), rendered.subject(),
                    rendered.text(), rendered.html(), attachment == null ? null : attachment.fileName(), attachment == null ? null : attachment.contentType(),
                    bytes, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            audit.record("booking-service", "email", stored.id(), "QUEUE", Map.of("kind", request.kind().name(), "reference", request.reference()));
            return new Accepted(stored, true);
        } catch (DuplicateKeyException e) {
            // Two requests with the same key at the same moment: the other one stored it.
            log.info("Email {} was stored by a concurrent request", request.dedupeKey());
            return new Accepted(emails.findByDedupeKey(request.dedupeKey()).orElseThrow(), false);
        }
    }
}
