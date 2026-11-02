package com.trainbooking.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.notification.notification.model.Email;
import com.trainbooking.notification.notification.model.EmailStatus;
import com.trainbooking.notification.notification.model.NotificationRequest;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real SMTP delivery into Mailpit (the dev profile of Compose: SMTP on localhost:1025, API on localhost:8025). Skipped, and shown as
 * SKIPPED, when Mailpit is not running. It proves what a fake cannot: that the message is well formed, has the text and HTML parts, and
 * carries the ticket PDF as an attachment.
 */
class SmtpEmailSenderMailpitTest {

    static boolean mailpitRunning() {
        try (Socket smtp = new Socket(); Socket api = new Socket()) {
            smtp.connect(new InetSocketAddress("localhost", 1025), 500);
            api.connect(new InetSocketAddress("localhost", 8025), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("mailpitRunning")
    void aTicketEmail_arrivesWithTextHtmlAndThePdfAttached() throws Exception {
        JavaMailSenderImpl javaMail = new JavaMailSenderImpl();
        javaMail.setHost("localhost");
        javaMail.setPort(1025);
        SmtpEmailSender sender = new SmtpEmailSender(javaMail, "tickets@trainbooking.local");
        String subject = "Mailpit test " + UUID.randomUUID();
        byte[] pdf = "%PDF-1.4 test ticket".getBytes(StandardCharsets.US_ASCII);

        sender.send(new Email(1, "k", NotificationRequest.Kind.TICKET, "TB-ABC234", "alice@example.com", "Alice", subject, "Plain text body", "<p>Html body</p>",
                "ticket-TB-ABC234.pdf", "application/pdf", pdf, EmailStatus.PENDING, 0, OffsetDateTime.now(), null, null));

        JsonMapper json = JsonMapper.builder().build();
        HttpClient http = HttpClient.newHttpClient();
        JsonNode found = null;
        for (int i = 0; i < 20 && found == null; i++) {
            HttpResponse<String> list = http.send(HttpRequest.newBuilder(URI.create("http://localhost:8025/api/v1/messages")).build(), HttpResponse.BodyHandlers.ofString());
            for (JsonNode message : json.readTree(list.body()).get("messages")) {
                if (subject.equals(message.get("Subject").asString())) {
                    found = message;
                }
            }
            if (found == null) {
                Thread.sleep(250);
            }
        }
        assertThat(found).as("the message arrived in Mailpit").isNotNull();
        assertThat(found.get("From").get("Address").asString()).isEqualTo("tickets@trainbooking.local");
        assertThat(found.get("To").get(0).get("Address").asString()).isEqualTo("alice@example.com");
        assertThat(found.get("Attachments").asInt()).isEqualTo(1);

        HttpResponse<String> detail = http.send(HttpRequest.newBuilder(URI.create("http://localhost:8025/api/v1/message/" + found.get("ID").asString())).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode message = json.readTree(detail.body());
        assertThat(message.get("Text").asString()).contains("Plain text body");
        assertThat(message.get("HTML").asString()).contains("<p>Html body</p>");
        assertThat(message.get("Attachments").get(0).get("FileName").asString()).isEqualTo("ticket-TB-ABC234.pdf");
        assertThat(message.get("Attachments").get(0).get("ContentType").asString()).isEqualTo("application/pdf");
    }
}
