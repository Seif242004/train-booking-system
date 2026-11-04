package com.trainbooking.bookingseat.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the user id, booking id and trip id of a request into the logging context (MDC) and writes one structured access line per request,
 * so every log record of the request carries them as fields in OpenObserve. The filter runs after the security filters, which is why the
 * verified token is known here.
 *
 * <p>Only the path is logged, never the query string, a header or a body: tokens and card data must not reach the logs.
 */
// @Component registers the filter as a bean; Spring Boot adds every Filter bean to the servlet filter chain automatically.
@Component
class RequestLogContext extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("access");
    // Finds the booking id in paths such as /bookings/17/cancel or /internal/payments/17/... so the log line carries bookingId.
    private static final Pattern BOOKING = Pattern.compile("/(?:bookings|payments|tickets|refunds)/(\\d+)");
    private static final Pattern TRIP = Pattern.compile("/trips/(\\d+)");

    @Override
    // OncePerRequestFilter guarantees one run per request. The try/finally clears the MDC fields even if the request fails, because threads
    // are reused by the server and a leftover userId would be printed on the next request's log lines.
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        String path = request.getRequestURI();
        // The user is the Keycloak subject of the verified token (the same plain value the services store); calls with the internal key
        // have none.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            MDC.put("userId", token.getName());
        }
        put("bookingId", BOOKING, path);
        put("tripId", TRIP, path);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.put("httpStatus", String.valueOf(response.getStatus()));
            log.info("{} {} -> {} in {} ms", request.getMethod(), path, response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            MDC.remove("userId");
            MDC.remove("bookingId");
            MDC.remove("tripId");
            MDC.remove("httpStatus");
        }
    }

    // Puts the first capture group of the pattern into the MDC under the key, if the path matches.
    private static void put(String key, Pattern pattern, String path) {
        Matcher matcher = pattern.matcher(path);
        if (matcher.find()) {
            MDC.put(key, matcher.group(1));
        }
    }
}
