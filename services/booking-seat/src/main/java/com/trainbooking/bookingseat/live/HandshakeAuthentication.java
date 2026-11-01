package com.trainbooking.bookingseat.live;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Checks the Keycloak token when a WebSocket connection is opened (CLAUDE.md, "WebSocket"). The Gateway has already verified it on
 * the upgrade; every service validates the token again itself, so a connection that reaches this service by any other path is
 * still refused. The browser cannot send an Authorization header on a WebSocket and the token must never be in the URL, so it comes
 * as a subprotocol: {@code Sec-WebSocket-Protocol: v12.stomp, access_token.<jwt>}. Nothing of the header is ever logged.
 * The user is the subject of the token; it becomes the name of the connection, which is how events are sent to one user only.
 */
@Component
public class HandshakeAuthentication implements HandshakeInterceptor {

    static final String SUBJECT = "sub";
    private static final String TOKEN_PREFIX = "access_token.";

    private final JwtDecoder decoder;

    public HandshakeAuthentication(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
        String token = tokenOf(request);
        if (token == null) {
            return refuse(response);
        }
        try {
            Jwt jwt = decoder.decode(token);
            attributes.put(SUBJECT, jwt.getSubject());
            return true;
        } catch (JwtException e) {
            return refuse(response); // bad signature, expired, wrong issuer, malformed: one answer for all
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
        // nothing to do
    }

    private static boolean refuse(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    /** The token from the subprotocols the browser offered, or from an Authorization header (for tools that can set one). */
    static String tokenOf(ServerHttpRequest request) {
        for (String offered : request.getHeaders().getOrDefault("Sec-WebSocket-Protocol", java.util.List.of())) {
            for (String protocol : offered.split(",")) {
                String trimmed = protocol.trim();
                if (trimmed.startsWith(TOKEN_PREFIX) && trimmed.length() > TOKEN_PREFIX.length()) {
                    return trimmed.substring(TOKEN_PREFIX.length());
                }
            }
        }
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7) && authorization.length() > 7) {
            return authorization.substring(7).trim();
        }
        return null;
    }
}
