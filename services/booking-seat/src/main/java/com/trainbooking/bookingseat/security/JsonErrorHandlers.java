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
// @Component: a bean that SecurityConfig receives by constructor injection and plugs into both filter chains as the 401 and 403 writer.
@Component
public class JsonErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;
    // Reused for its WWW-Authenticate: Bearer ... challenge header, which clients and proxies expect on a 401.
    private final BearerTokenAuthenticationEntryPoint bearerChallenge = new BearerTokenAuthenticationEntryPoint();

    public JsonErrorHandlers(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    // AuthenticationEntryPoint: called when a request needs a login and has none (or a bad one) -> 401. The body tells the two cases apart:
    // "unauthenticated" (no Authorization header at all) and "invalid_token" (header present but the JWT failed the checks).
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        bearerChallenge.commence(request, response, authException); // sets the status and the WWW-Authenticate header
        boolean noCredentials = request.getHeader("Authorization") == null;
        write(response, HttpStatus.UNAUTHORIZED, noCredentials
                ? ApiError.of("unauthenticated", "A bearer token is required.")
                : ApiError.of("invalid_token", "The token is not valid."));
    }

    @Override
    // AccessDeniedHandler: called when the user is logged in but lacks the role -> 403. Only /admin/** is role-protected here.
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, ApiError.of("forbidden", "The ADMIN role is required."));
    }

    // Writes the JSON error by hand because no controller (and so no GlobalExceptionHandler) is involved at this stage.
    private void write(HttpServletResponse response, HttpStatus status, ApiError body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
