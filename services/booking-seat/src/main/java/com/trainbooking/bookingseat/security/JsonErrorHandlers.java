package com.trainbooking.bookingseat.security;

import com.trainbooking.bookingseat.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security failures happen in the filter chain, before any controller runs, so the controller advice cannot answer
 * them. These handlers write the same JSON error shape as the rest of the API:
 * 401 when the token is missing or invalid, 403 when it is valid but the role is missing.
 */
@Component
public class JsonErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;
    // Reused for its WWW-Authenticate: Bearer ... challenge header, which clients and proxies expect on a 401.
    private final BearerTokenAuthenticationEntryPoint bearerChallenge = new BearerTokenAuthenticationEntryPoint();

    public JsonErrorHandlers(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        bearerChallenge.commence(request, response, authException); // sets the status and the WWW-Authenticate header
        boolean noCredentials = request.getHeader("Authorization") == null;
        write(response, HttpStatus.UNAUTHORIZED, noCredentials
                ? ApiError.of("unauthenticated", "A bearer token is required.")
                : ApiError.of("invalid_token", "The token is not valid."));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, ApiError.of("forbidden", "The ADMIN role is required."));
    }

    private void write(HttpServletResponse response, HttpStatus status, ApiError body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
