package com.trainbooking.notification.mail;

import com.trainbooking.notification.notification.model.Email;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Sends emails through Spring Mail (SMTP): text and HTML in one message, and the ticket PDF attached. */
// @Component: one instance, injected wherever an EmailSender is needed. Package-private on purpose: callers depend on the interface.
// JavaMailSender is configured by Spring Boot from the spring.mail.* properties (host, port, login, TLS).
@Component
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    SmtpEmailSender(JavaMailSender mail, @Value("${trainbooking.mail.from}") String from) {
        this.mail = mail;
        this.from = from;
    }

    // Builds a multipart MIME message (text and HTML alternatives, plus the PDF) and hands it to the SMTP server. Any runtime exception
    // (server down, address refused) propagates to EmailDispatcher, which schedules a retry. No transaction is open here.
    @Override
    public void send(Email email) {
        try {
            MimeMessage message = mail.createMimeMessage();
            // The "true" asks for a multipart message, needed for the HTML alternative and the attachment.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email.recipient());
            helper.setSubject(email.subject());
            // Two bodies in one message: mail clients that cannot show HTML fall back to the plain text.
            helper.setText(email.bodyText(), email.bodyHtml());
            if (email.attachment() != null) {
                helper.addAttachment(email.attachmentName(), new ByteArrayResource(email.attachment()), email.attachmentType());
            }
            mail.send(message);
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException("The email could not be built: " + e.getMessage(), e);
        }
    }
}
