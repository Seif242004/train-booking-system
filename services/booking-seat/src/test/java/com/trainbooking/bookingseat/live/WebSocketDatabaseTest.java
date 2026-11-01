package com.trainbooking.bookingseat.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.trainbooking.bookingseat.booking.model.AuthenticatedUser;
import com.trainbooking.bookingseat.booking.model.BookingView;
import com.trainbooking.bookingseat.booking.model.HoldConflictException;
import com.trainbooking.bookingseat.booking.model.HoldRequest;
import com.trainbooking.bookingseat.booking.service.ExpiryService;
import com.trainbooking.bookingseat.booking.service.HoldService;
import com.trainbooking.bookingseat.testsupport.DatabaseTest;
import com.trainbooking.bookingseat.testsupport.RequiresTestDatabase;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live updates with a real STOMP client over a real WebSocket (SRS section 6, Flow 2): the token is checked at the handshake,
 * events arrive only after the commit and only for the trip (or the user) they belong to, with a version that goes up by one, and
 * the client cannot send anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "trainbooking.internal-api-key=" + DatabaseTest.INTERNAL_KEY,
        "trainbooking.sweeper.enabled=false",
        "trainbooking.ws.allowed-origin=http://localhost:5173",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/not-used"
})
@AutoConfigureMockMvc
@Import(com.trainbooking.bookingseat.testsupport.DatabaseTestConfig.class)
@RequiresTestDatabase
class WebSocketDatabaseTest extends DatabaseTest {

    private static final long TRIP = 9501;
    private static final AuthenticatedUser ALICE = new AuthenticatedUser("alice-sub", "alice@example.com", "Alice");
    private static final AuthenticatedUser BOB = new AuthenticatedUser("bob-sub", "bob@example.com", "Bob");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @MockitoBean
    JwtDecoder decoder;

    @Autowired
    HoldService holds;

    @Autowired
    ExpiryService expiry;

    private final List<WebSocketStompClient> clients = new ArrayList<>();

    @BeforeEach
    void aTripAndTokens() {
        insertTrip(TRIP);
        given(decoder.decode(anyString())).willAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (token.equals("alice-token")) return jwt("alice-sub");
            if (token.equals("bob-token")) return jwt("bob-sub");
            throw new BadJwtException("not a valid token");
        });
    }

    @AfterEach
    void closeClients() {
        clients.forEach(WebSocketStompClient::stop);
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private StompSession connect(String token, String origin) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new RawTextConverter());
        clients.add(client);
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setOrigin(origin);
        // The token is offered as a subprotocol next to the real one, exactly as the browser does it.
        handshake.setSecWebSocketProtocol(token == null ? List.of("v12.stomp") : List.of("v12.stomp", "access_token." + token));
        return client.connectAsync("ws://localhost:" + port + "/ws", handshake, new StompHeaders(), new StompSessionHandlerAdapter() { })
                .get(5, TimeUnit.SECONDS);
    }

    private StompSession connect(String token) throws Exception {
        return connect(token, "http://localhost:5173");
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session, String destination) {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(JSON.readTree((String) payload));
            }
        });
        return received;
    }

    private static HoldRequest seats(long... seatIds) {
        return new HoldRequest(TRIP, 0, 3, java.util.Arrays.stream(seatIds).boxed().toList(), List.of());
    }

    // ---- who may connect ------------------------------------------------------------------------------------------------

    @Test
    void withoutATokenOrWithABadOne_theConnectionIsRefused() {
        assertThatThrownBy(() -> connect(null)).hasMessageContaining("401");
        assertThatThrownBy(() -> connect("garbage")).hasMessageContaining("401");
    }

    @Test
    void anotherOrigin_isRefused_evenWithAValidToken() {
        assertThatThrownBy(() -> connect("alice-token", "http://evil.example")).hasMessageContaining("403");
    }

    // ---- events ----------------------------------------------------------------------------------------------------------

    @Test
    void aHoldIsPushedToEveryoneLookingAtTheTrip_afterTheCommit_withTheNextVersion() throws Exception {
        BlockingQueue<JsonNode> alice = subscribe(connect("alice-token"), "/topic/trips/" + TRIP);
        BlockingQueue<JsonNode> bob = subscribe(connect("bob-token"), "/topic/trips/" + TRIP);
        Thread.sleep(300); // the subscriptions are registered on the server

        holds.hold(BOB, new HoldRequest(TRIP, 0, 3, List.of(1L), List.of(new HoldRequest.StandingRequest(30L, 2))));

        for (BlockingQueue<JsonNode> viewer : List.of(alice, bob)) {
            JsonNode held = viewer.poll(3, TimeUnit.SECONDS);
            assertThat(held).isNotNull();
            assertThat(held.get("type").asString()).isEqualTo("SEAT_HELD");
            assertThat(held.get("tripId").asLong()).isEqualTo(TRIP);
            assertThat(held.get("seatIds").get(0).asLong()).isEqualTo(1);
            assertThat(held.get("segments").size()).isEqualTo(3);
            assertThat(held.get("version").asLong()).isEqualTo(1);
            JsonNode standing = viewer.poll(3, TimeUnit.SECONDS);
            assertThat(standing.get("type").asString()).isEqualTo("STANDING_CHANGED");
            assertThat(standing.get("coachId").asLong()).isEqualTo(30);
            assertThat(standing.get("delta").asInt()).isEqualTo(-2);
            assertThat(standing.get("version").asLong()).isEqualTo(2);
        }
    }

    @Test
    void aHoldThatWasRefused_isNeverPushed_becauseItsTransactionRolledBack() throws Exception {
        holds.hold(BOB, seats(1L));
        BlockingQueue<JsonNode> alice = subscribe(connect("alice-token"), "/topic/trips/" + TRIP);
        Thread.sleep(300);

        assertThatThrownBy(() -> holds.hold(ALICE, seats(2L, 1L))).isInstanceOf(HoldConflictException.class);

        assertThat(alice.poll(1500, TimeUnit.MILLISECONDS)).isNull(); // seat 2 was not held, so nothing happened
        assertThat(rows("trip_version WHERE version = 1")).isEqualTo(1); // and the counter did not move
    }

    @Autowired
    AvailabilityEvents events;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transaction;

    @Test
    void anEventPublishedInATransactionThatRollsBack_isNeverPushed_nobodySeesStateThatWasNotCommitted() throws Exception {
        BlockingQueue<JsonNode> alice = subscribe(connect("alice-token"), "/topic/trips/" + TRIP);
        Thread.sleep(300);

        transaction.executeWithoutResult(status -> {
            events.seats(SeatEvent.SEAT_HELD, TRIP, List.of(1L), List.of(0, 1, 2)); // announced inside the transaction ...
            status.setRollbackOnly(); // ... which then fails
        });

        assertThat(alice.poll(1500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(committed.seatEvents()).isEmpty();
        assertThat(rows("trip_version WHERE version = 0")).isEqualTo(1); // the counter rolled back with it

        transaction.executeWithoutResult(status -> events.seats(SeatEvent.SEAT_HELD, TRIP, List.of(1L), List.of(0, 1, 2))); // committed this time
        assertThat(alice.poll(3, TimeUnit.SECONDS).get("version").asLong()).isEqualTo(1);
    }

    @Test
    void theEventsOfOneTripDoNotReachTheViewersOfAnother() throws Exception {
        BlockingQueue<JsonNode> elsewhere = subscribe(connect("alice-token"), "/topic/trips/123456");
        Thread.sleep(300);

        holds.hold(BOB, seats(1L));

        assertThat(elsewhere.poll(1500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void severalChanges_arriveWithVersionsThatGoUpByOne() throws Exception {
        BlockingQueue<JsonNode> alice = subscribe(connect("alice-token"), "/topic/trips/" + TRIP);
        Thread.sleep(300);

        holds.hold(BOB, seats(1L));
        holds.hold(BOB, seats(2L));
        holds.hold(BOB, seats(3L));

        List<Long> versions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            versions.add(alice.poll(3, TimeUnit.SECONDS).get("version").asLong());
        }
        assertThat(versions).containsExactly(1L, 2L, 3L);
    }

    @Test
    void whenAHoldExpires_theReleaseIsPushedToEveryone_andHoldExpiredOnlyToItsUser() throws Exception {
        BlockingQueue<JsonNode> bobTopic = subscribe(connect("bob-token"), "/topic/trips/" + TRIP);
        StompSession aliceSession = connect("alice-token");
        BlockingQueue<JsonNode> aliceQueue = subscribe(aliceSession, "/user/queue/holds");
        BlockingQueue<JsonNode> bobQueue = subscribe(connect("bob-token"), "/user/queue/holds");
        Thread.sleep(300);
        BookingView booking = holds.hold(ALICE, seats(1L));
        bobTopic.poll(3, TimeUnit.SECONDS); // SEAT_HELD

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        expiry.sweep();

        JsonNode released = bobTopic.poll(3, TimeUnit.SECONDS);
        assertThat(released.get("type").asString()).isEqualTo("SEAT_RELEASED");
        JsonNode expired = aliceQueue.poll(3, TimeUnit.SECONDS);
        assertThat(expired).isNotNull();
        assertThat(expired.get("type").asString()).isEqualTo("HOLD_EXPIRED");
        assertThat(expired.get("bookingId").asLong()).isEqualTo(booking.id());
        assertThat(expired.get("reason").asString()).isEqualTo("EXPIRED");
        assertThat(bobQueue.poll(1500, TimeUnit.MILLISECONDS)).isNull(); // another user's queue never gets it
    }

    // ---- what a client may do ------------------------------------------------------------------------------------------------

    @Test
    void aClientCannotSend_andCannotSubscribeToAnythingElse() throws Exception {
        StompSession sender = connect("alice-token");
        sender.send("/app/anything", "hello");
        awaitClosed(sender);

        StompSession spy = connect("alice-token");
        spy.subscribe("/topic/everything", new StompSessionHandlerAdapter() { });
        awaitClosed(spy);

        StompSession other = connect("alice-token");
        other.subscribe("/user/bob-sub/queue/holds", new StompSessionHandlerAdapter() { });
        awaitClosed(other);
    }

    /** The events are JSON; the test reads them as text whatever their content type is. */
    private static final class RawTextConverter implements MessageConverter {
        @Override
        public Object fromMessage(Message<?> message, Class<?> targetClass) {
            Object payload = message.getPayload();
            return payload instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : payload;
        }

        @Override
        public Message<?> toMessage(Object payload, MessageHeaders headers) {
            return MessageBuilder.createMessage(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), headers);
        }
    }

    private static void awaitClosed(StompSession session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (session.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(session.isConnected()).as("the server closes a connection that does what it must not").isFalse();
    }
}
