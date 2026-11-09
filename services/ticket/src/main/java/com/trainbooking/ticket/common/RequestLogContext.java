package com.trainbooking.ticket.common;

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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the user id, booking id and trip id of a request into the logging context (MDC) and writes one structured access line per request,
 * so every log record of the request carries them as fields in OpenObserve. The filter runs after the security filters, which is why the
 * verified token is known here.
 *
 * <p>Only the path is logged, never the query string, a header or a body: tokens and card data must not reach the logs.
 */
// {@code @Component} registers the filter as a bean; Spring Boot then adds every Filter bean to the servlet filter chain.
@Component
class RequestLogContext extends OncePerRequestFilter {

    // Logger named "access": one line per request, filterable in OpenObserve.
    private static final Logger log = LoggerFactory.getLogger("access");
    // Takes the id after /bookings/, /payments/, /tickets/ or /refunds/ in the path (in this service: /internal/tickets/{bookingId}).
    private static final Pattern BOOKING = Pattern.compile("/(?:bookings|payments|tickets|refunds)/(\\d+)");
    private static final Pattern TRIP = Pattern.compile("/trips/(\\d+)");

    @Override
    // {@code OncePerRequestFilter} guarantees one run per request. The ids are put in the MDC before the request is handled, so that every
    // log line written while handling it carries them; the access line is written after, when the status and the duration are known.
    // The finally block runs also when the request fails, and removes the fields so the next request on this pooled thread starts clean.
    // userId is removed too although this service never sets it (it has no user token; the caller is another service).
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        String path = request.getRequestURI();
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

    // Puts the first capture group of the pattern in the MDC when the path matches.
    private static void put(String key, Pattern pattern, String path) {
        Matcher matcher = pattern.matcher(path);
        if (matcher.find()) {
            MDC.put(key, matcher.group(1));
        }
    }
}
