package com.trainbooking.ticket.security;

import com.trainbooking.ticket.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Security failures happen before any controller runs, so they are written here in the same JSON error shape as everything else. */
// {@code @Component} makes it a bean so SecurityConfig can receive it. It plays two Spring Security roles at once.
@Component
public class JsonErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public JsonErrorHandlers(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    // AuthenticationEntryPoint: called when a request has no valid login. Always 401 invalid_api_key, because the key is the only login.
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, ApiError.of("invalid_api_key", "A valid X-Internal-Api-Key header is required."));
    }

    @Override
    // AccessDeniedHandler: called when the caller is known but not allowed. 403 forbidden.
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, ApiError.of("forbidden", "This route is not available."));
    }

    // Writes status, content type and the JSON error body by hand, because these failures happen in the filter chain, before Spring MVC
    // (and so before GlobalExceptionHandler) is reached.
    private void write(HttpServletResponse response, HttpStatus status, ApiError body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
