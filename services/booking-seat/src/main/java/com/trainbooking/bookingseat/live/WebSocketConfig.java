package com.trainbooking.bookingseat.live;

import java.security.Principal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * The live seat updates (Flow 2, SRS section 6): Spring WebSocket with STOMP, pushed to the browser through the Gateway.
 * <ul>
 *   <li>{@code /ws} is the endpoint; the token is checked at the handshake ({@link HandshakeAuthentication});</li>
 *   <li>{@code /topic/trips/{id}} carries the events of one trip, {@code /user/queue/holds} the events of one user;</li>
 *   <li>there are no application destinations: the browser cannot send ({@link SubscriptionGuard}).</li>
 * </ul>
 * A single Booking and Seat instance is assumed, so the in-memory broker is enough (SRS section 6).
 */
@Configuration
// @EnableWebSocketMessageBroker turns on STOMP over WebSocket (STOMP = a simple text protocol with SUBSCRIBE / SEND frames on top of a
// socket).
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final HandshakeAuthentication authentication;
    private final String allowedOrigin;

    public WebSocketConfig(HandshakeAuthentication authentication, @Value("${trainbooking.ws.allowed-origin}") String allowedOrigin) {
        this.authentication = authentication;
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // The browser connects to /ws (through the Gateway). The interceptor checks the token, the handshake handler names the user.
        registry.addEndpoint("/ws")
                .addInterceptors(authentication)
                .setHandshakeHandler(new SubjectHandshakeHandler())
                // The Gateway passes the browser's Origin on: only the web app may open a connection.
                .setAllowedOrigins(allowedOrigin);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // The simple broker lives in memory inside this JVM: it delivers /topic (to all subscribers) and /queue (to one user). With two
        // Booking
        // instances an event would reach only the clients of the instance that made it (known limit; the project runs one instance).
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Every frame from a client passes the guard first.
        registration.interceptors(new SubscriptionGuard());
    }

    /** The name of a connection is the subject of its token, so that an event can be sent to one user. */
    // Spring uses the "Principal" of a connection to route /user/** messages; here the principal name is the Keycloak sub.
    private static final class SubjectHandshakeHandler extends DefaultHandshakeHandler {
        @Override
        protected Principal determineUser(ServerHttpRequest request, WebSocketHandler handler, Map<String, Object> attributes) {
            String subject = (String) attributes.get(HandshakeAuthentication.SUBJECT);
            return () -> subject;
        }
    }
}
