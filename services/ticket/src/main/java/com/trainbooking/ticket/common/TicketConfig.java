package com.trainbooking.ticket.common;

import com.trainbooking.ticket.ticket.service.QrTokens;
import com.trainbooking.ticket.ticket.service.TicketPdfRenderer;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock, the signing key of the QR codes and the time zone of the times printed on the ticket. */
@Configuration
public class TicketConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    QrTokens qrTokens(@Value("${trainbooking.ticket.signing-key}") String signingKey) {
        return new QrTokens(signingKey);
    }

    @Bean
    TicketPdfRenderer ticketPdfRenderer(@Value("${trainbooking.timezone}") String timezone) {
        return new TicketPdfRenderer(ZoneId.of(timezone));
    }
}
