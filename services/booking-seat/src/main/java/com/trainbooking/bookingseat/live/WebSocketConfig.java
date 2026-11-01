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
        registry.addEndpoint("/ws")
                .addInterceptors(authentication)
                .setHandshakeHandler(new SubjectHandshakeHandler())
                // The Gateway passes the browser's Origin on: only the web app may open a connection.
                .setAllowedOrigins(allowedOrigin);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new SubscriptionGuard());
    }

    /** The name of a connection is the subject of its token, so that an event can be sent to one user. */
    private static final class SubjectHandshakeHandler extends DefaultHandshakeHandler {
        @Override
        protected Principal determineUser(ServerHttpRequest request, WebSocketHandler handler, Map<String, Object> attributes) {
            String subject = (String) attributes.get(HandshakeAuthentication.SUBJECT);
            return () -> subject;
        }
    }
}
