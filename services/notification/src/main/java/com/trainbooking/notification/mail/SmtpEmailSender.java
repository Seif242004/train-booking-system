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
@Component
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    SmtpEmailSender(JavaMailSender mail, @Value("${trainbooking.mail.from}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void send(Email email) {
        try {
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email.recipient());
            helper.setSubject(email.subject());
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
