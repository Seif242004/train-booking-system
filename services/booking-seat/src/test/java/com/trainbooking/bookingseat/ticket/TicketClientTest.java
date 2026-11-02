package com.trainbooking.bookingseat.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

/** The HTTP client of the Ticket service against a small real server. */
class TicketClientTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private volatile int status = 200;
    private volatile byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " key=" + exchange.getRequestHeaders().getFirst("X-Internal-Api-Key")
                    + " body=" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private TicketClient client() {
        return new TicketClient("http://127.0.0.1:" + server.getAddress().getPort(), "secret-key", 2000);
    }

    private static TicketPort.TicketData data() {
        return new TicketPort.TicketData("TB-ABC234", "Alice", "T100", "Express", "Cairo", OffsetDateTime.parse("2026-11-02T06:00:00Z"), "Alexandria",
                OffsetDateTime.parse("2026-11-02T08:45:00Z"), List.of(new TicketPort.Line("SEAT", "A1", "FIRST", "1A", 1, new BigDecimal("230.00"))), new BigDecimal("230.00"), "EGP");
    }

    @Test
    void issue_putsTheTicketData_withTheKey() {
        client().issue(7, data());

        assertThat(requests.get(0)).startsWith("PUT /internal/tickets/7 key=secret-key")
                .contains("\"reference\":\"TB-ABC234\"", "\"passengerName\":\"Alice\"", "\"fromStation\":\"Cairo\"", "\"amount\":230.00", "\"seatNumber\":\"1A\"");
        assertThat(requests.get(0)).contains("2026-11-02T06:00:00Z"); // times as ISO strings, not numbers
    }

    @Test
    void pdf_returnsTheBytes_andAnEmptyAnswerIsNotAPdf() {
        body = "%PDF-1.4 x".getBytes(StandardCharsets.UTF_8);
        assertThat(client().pdf(7)).isEqualTo(body);

        body = new byte[0];
        assertThatThrownBy(() -> client().pdf(7)).isInstanceOf(RemoteServiceException.class);
    }

    @Test
    void invalidate_ofABookingWithoutATicket_isNotAnError() {
        status = 404;
        body = "{\"error\":{\"code\":\"ticket_not_found\",\"message\":\"x\"}}".getBytes(StandardCharsets.UTF_8);

        assertThatCode(() -> client().invalidate(7)).doesNotThrowAnyException();
    }

    @Test
    void anyOtherFailure_meansTheJobMustBeRepeated() {
        status = 500;
        assertThatThrownBy(() -> client().issue(7, data())).isInstanceOf(RemoteServiceException.class);
        assertThatThrownBy(() -> client().invalidate(7)).isInstanceOf(RemoteServiceException.class);

        status = 401; // a wrong key is a deployment problem, never "nothing to do"
        assertThatThrownBy(() -> client().invalidate(7)).isInstanceOf(RemoteServiceException.class);

        status = 404; // a 404 that is not "no ticket" (a wrong URL, say) is not silently ignored either
        body = "{\"error\":{\"code\":\"not_found\",\"message\":\"x\"}}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client().invalidate(7)).isInstanceOf(RemoteServiceException.class);
    }

    @Test
    void aTicketServiceThatIsNotRunning_isReportedAsUnreachable() {
        server.stop(0);

        assertThatThrownBy(() -> client().issue(7, data())).isInstanceOf(RemoteServiceException.class).hasMessageContaining("could not be reached");
    }
}
