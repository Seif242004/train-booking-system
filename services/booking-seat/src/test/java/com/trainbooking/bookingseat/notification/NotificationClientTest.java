package com.trainbooking.bookingseat.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.trainbooking.bookingseat.common.RemoteServiceException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The HTTP client of the Notification service against a small real server. */
class NotificationClientTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private volatile int status = 202;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " key=" + exchange.getRequestHeaders().getFirst("X-Internal-Api-Key")
                    + " body=" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"id\":1,\"status\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private NotificationClient client() {
        return new NotificationClient("http://127.0.0.1:" + server.getAddress().getPort(), "secret-key", 2000);
    }

    private static NotificationPort.Email email() {
        return new NotificationPort.Email("ticket:7:1", NotificationPort.Kind.TICKET, "alice@example.com", "Alice", "TB-ABC234",
                new NotificationPort.Trip("T100 Express", "Cairo", OffsetDateTime.parse("2026-11-02T06:00:00Z"), "Alexandria", OffsetDateTime.parse("2026-11-02T08:45:00Z")),
                List.of("Seat 1A"), new BigDecimal("230.00"), "EGP", null, new NotificationPort.Attachment("t.pdf", "application/pdf", "JVBERg=="));
    }

    @Test
    void send_postsTheEmailRequest_withTheKey_andAcceptsBothAnswers() {
        client().send(email());
        status = 200; // the same dedupe key was accepted before
        client().send(email());

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).startsWith("POST /internal/notifications key=secret-key")
                .contains("\"dedupeKey\":\"ticket:7:1\"", "\"kind\":\"TICKET\"", "\"to\":\"alice@example.com\"", "\"contentBase64\":\"JVBERg==\"");
    }

    @Test
    void aRefusalOrAnOutage_meansTheJobMustBeRepeated() {
        status = 400;
        assertThatThrownBy(() -> client().send(email())).isInstanceOf(RemoteServiceException.class);
        status = 503;
        assertThatThrownBy(() -> client().send(email())).isInstanceOf(RemoteServiceException.class);
        server.stop(0);
        assertThatThrownBy(() -> client().send(email())).isInstanceOf(RemoteServiceException.class);
    }
}
