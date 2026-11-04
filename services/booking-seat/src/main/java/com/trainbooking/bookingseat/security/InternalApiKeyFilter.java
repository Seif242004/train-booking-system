package com.trainbooking.bookingseat.security;

import com.trainbooking.bookingseat.common.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Protects the service-to-service endpoints under /internal: the caller must send the shared key from the
 * {@code INTERNAL_API_KEY} setting in the {@code X-Internal-Api-Key} header. A user's JWT is not accepted there and the
 * Gateway never forwards /internal, so only another service holding the key can reach it.
 * This filter is created by {@link SecurityConfig} and is deliberately not a Spring bean, so that the servlet container
 * does not also register it for every request.
 */
// Not annotated on purpose (see above). A "filter" is code that runs for each HTTP request before the controller; OncePerRequestFilter
// makes sure it runs once even if the request is forwarded internally.
class InternalApiKeyFilter extends OncePerRequestFilter {

    // The header name callers send, and the authority granted to a caller that proves it knows the key. hasAuthority(ROLE) in
    // SecurityConfig
    // checks it, so a user JWT (which has ROLE_USER / ROLE_ADMIN) can never pass.
    static final String HEADER = "X-Internal-Api-Key";
    static final String ROLE = "ROLE_INTERNAL";

    private final byte[] expectedKey;
    private final JsonMapper jsonMapper;

    // Fail fast: with no key the service refuses to start, because an empty key would leave /internal open to everybody.
    InternalApiKeyFilter(String key, JsonMapper jsonMapper) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_KEY must be set: it protects the /internal endpoints.");
        }
        this.expectedKey = key.getBytes(StandardCharsets.UTF_8);
        this.jsonMapper = jsonMapper;
    }

    @Override
    // @Override: replaces the method of the parent class that the filter chain calls for each request.
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sent = request.getHeader(HEADER);
        // MessageDigest.isEqual compares in constant time, so the response time does not reveal how much of a guess matched.
        if (sent == null || !MessageDigest.isEqual(expectedKey, sent.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            jsonMapper.writeValue(response.getOutputStream(), ApiError.of("invalid_api_key", "A valid " + HEADER + " header is required."));
            return;
        }
        // Marks the request as authenticated for the rest of the chain, with a fixed principal name and only the INTERNAL role.
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("internal-caller", null, List.of(new SimpleGrantedAuthority(ROLE))));
        chain.doFilter(request, response);
    }
}
