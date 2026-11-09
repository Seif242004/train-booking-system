package com.trainbooking.ticket.common;

import com.trainbooking.ticket.ticket.service.QrTokens;
import com.trainbooking.ticket.ticket.service.TicketPdfRenderer;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock, the signing key of the QR codes and the time zone of the times printed on the ticket. */
// {@code @Configuration} marks a class whose {@code @Bean} methods build beans by hand. Spring calls each method once and keeps the result.
@Configuration
public class TicketConfig {

    @Bean
    // {@code @Bean}: the object returned becomes a bean that others can ask for. One UTC clock is shared, so tests can replace it
    // with a clock they move by hand (MutableClock) and the time of tickets and invalidations never depends on the machine zone.
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    // {@code @Value("${...}")} injects a setting from application.properties or the environment. The key comes from TICKET_SIGNING_KEY; the
    // QrTokens constructor refuses a missing or short key, so the service does not start with a weak one.
    QrTokens qrTokens(@Value("${trainbooking.ticket.signing-key}") String signingKey) {
        return new QrTokens(signingKey);
    }

    @Bean
    // The renderer prints the times in this zone (TIMEZONE, default Africa/Cairo), whatever the JVM or database session zone is.
    TicketPdfRenderer ticketPdfRenderer(@Value("${trainbooking.timezone}") String timezone) {
        return new TicketPdfRenderer(ZoneId.of(timezone));
    }
}
