package com.trainbooking.bookingseat.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The HTTP client of the Payment service against a small real server: what each answer means to the callers. */
class PaymentClientTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMs = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " key=" + exchange.getRequestHeaders().getFirst("X-Internal-Api-Key")
                    + " body=" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                // the client gave up
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // the client gave up
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private PaymentClient client(long readTimeoutMs) {
        return new PaymentClient("http://127.0.0.1:" + server.getAddress().getPort(), "secret-key", readTimeoutMs);
    }

    @Test
    void cancelAndInvalidate_sendTheKey_andReadTheOutcome() {
        body = "{\"outcome\":\"SUCCEEDED\"}";

        assertThat(client(2000).cancelPending(7)).isEqualTo(PaymentPort.CancelOutcome.SUCCEEDED);
        body = "{\"outcome\":\"NONE\"}";
        assertThat(client(2000).invalidate(7)).isEqualTo(PaymentPort.CancelOutcome.NONE);

        assertThat(requests).containsExactly("POST /internal/payments/7/cancel key=secret-key body=", "POST /internal/payments/7/invalidate key=secret-key body=");
    }

    @Test
    void createPayment_sendsTheAmount_andReturnsTheSession() {
        body = "{\"paymentId\":3,\"clientSecret\":\"pi_1_secret_x\",\"publishableKey\":\"pk_test_1\",\"amount\":230.00,\"currency\":\"EGP\"}";

        PaymentPort.PaymentSession session = client(2000).createPayment(7, new BigDecimal("230.00"), "EGP");

        assertThat(session.clientSecret()).isEqualTo("pi_1_secret_x");
        assertThat(session.publishableKey()).isEqualTo("pk_test_1");
        assertThat(requests.get(0)).contains("POST /internal/payments key=secret-key").contains("\"bookingId\":7").contains("\"amount\":230.00").contains("\"currency\":\"EGP\"");
    }

    @Test
    void a409_isARefusal_withItsCode() {
        status = 409;
        body = "{\"error\":{\"code\":\"payment_closed\",\"message\":\"x\"}}";

        assertThatThrownBy(() -> client(2000).createPayment(7, BigDecimal.TEN, "EGP"))
                .isInstanceOf(PaymentRejectedException.class).extracting("code").isEqualTo("payment_closed");
    }

    @Test
    void aStripeThatIsNotSetUp_isUnavailableWithItsOwnCode() {
        status = 503;
        body = "{\"error\":{\"code\":\"stripe_not_configured\",\"message\":\"x\"}}";

        assertThatThrownBy(() -> client(2000).createPayment(7, BigDecimal.TEN, "EGP"))
                .isInstanceOf(PaymentUnavailableException.class).extracting("code").isEqualTo("stripe_not_configured");
    }

    @Test
    void anyOtherFailure_meansTheAnswerIsNotKnown() {
        status = 500;
        assertThatThrownBy(() -> client(2000).cancelPending(7)).isInstanceOf(PaymentUnavailableException.class);

        status = 401; // a wrong key is a deployment problem, never "nothing to cancel"
        assertThatThrownBy(() -> client(2000).invalidate(7)).isInstanceOf(PaymentUnavailableException.class);

        status = 200;
        body = "{\"outcome\":\"MAYBE\"}"; // an answer we do not understand is not an answer
        assertThatThrownBy(() -> client(2000).cancelPending(7)).isInstanceOf(PaymentUnavailableException.class);
        body = "{}";
        assertThatThrownBy(() -> client(2000).cancelPending(7)).isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void aSlowPayment_isATimeout_notAHang() {
        delayMs = 1500;
        body = "{\"outcome\":\"NONE\"}";

        assertThatThrownBy(() -> client(300).cancelPending(7)).isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void aPaymentThatIsNotRunning_isUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> client(300).cancelPending(7)).isInstanceOf(PaymentUnavailableException.class);
        assertThat(new AtomicInteger(requests.size()).get()).isZero();
    }
}
