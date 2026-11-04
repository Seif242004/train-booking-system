package com.trainbooking.trainschedule.common;

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
// @Component: as a servlet Filter bean Spring Boot adds it to the web server's filter chain automatically.
// OncePerRequestFilter guarantees doFilterInternal runs exactly once per request, even with internal forwards.
@Component
class RequestLogContext extends OncePerRequestFilter {

    /** Logger named "access": one line per request, kept apart from the application logs. */
    private static final Logger log = LoggerFactory.getLogger("access");
    /** Finds the booking id in paths like /bookings/42/cancel. Train &amp; Schedule has none, but all services share this filter. */
    private static final Pattern BOOKING = Pattern.compile("/(?:bookings|payments|tickets|refunds)/(\\d+)");
    /** Finds the trip id in paths like /admin/trips/7 or /trips/7/layout. */
    private static final Pattern TRIP = Pattern.compile("/trips/(\\d+)");

    /**
     * Fills the MDC (the per-thread map that the JSON log layout copies into every log record), runs the rest of the chain,
     * then writes the access line and clears the MDC so ids never leak into the next request on the same pooled thread.
     */
    // @Override: replaces the abstract method of OncePerRequestFilter.
    @Override
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
            // finally: the line is written even when the request failed with an exception.
            MDC.put("httpStatus", String.valueOf(response.getStatus()));
            log.info("{} {} -> {} in {} ms", request.getMethod(), path, response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            MDC.remove("userId");
            MDC.remove("bookingId");
            MDC.remove("tripId");
            MDC.remove("httpStatus");
        }
    }

    /** Puts the first captured group of {@code pattern} (a numeric id) into the MDC under {@code key}, if the path matches. */
    private static void put(String key, Pattern pattern, String path) {
        Matcher matcher = pattern.matcher(path);
        if (matcher.find()) {
            MDC.put(key, matcher.group(1));
        }
    }
}
