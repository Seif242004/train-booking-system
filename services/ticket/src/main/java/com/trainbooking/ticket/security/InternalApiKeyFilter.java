package com.trainbooking.ticket.security;

import com.trainbooking.ticket.common.ApiError;
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
class InternalApiKeyFilter extends OncePerRequestFilter {

    // The name of the header and the authority that this filter gives to a caller who proved it holds the key.
    static final String HEADER = "X-Internal-Api-Key";
    static final String ROLE = "ROLE_INTERNAL";

    // Both sides are compared as bytes, so the comparison does not depend on how characters are encoded.
    private final byte[] expectedKey;
    private final JsonMapper jsonMapper;

    // Fails at start-up (not at the first request) when the key is missing: a service with no key would otherwise be open or always closed.
    InternalApiKeyFilter(String key, JsonMapper jsonMapper) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_KEY must be set: it protects the /internal endpoints.");
        }
        this.expectedKey = key.getBytes(StandardCharsets.UTF_8);
        this.jsonMapper = jsonMapper;
    }

    // {@code @Override}: this method replaces the one of OncePerRequestFilter, and the compiler checks the signature.
    @Override
    // 1. Read the header. 2. Compare in constant time. 3. Wrong or missing: answer 401 in the shared JSON error shape and stop (the chain
    // is not called, so no controller runs). 4. Right: put an authenticated "internal-caller" into the security context with the
    // authority ROLE_INTERNAL and continue. The security context is per request (the chain is stateless), so nothing is remembered.
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
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("internal-caller", null, List.of(new SimpleGrantedAuthority(ROLE))));
        chain.doFilter(request, response);
    }
}
