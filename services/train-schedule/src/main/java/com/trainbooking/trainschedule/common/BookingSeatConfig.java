package com.trainbooking.trainschedule.common;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client for Booking and Seat. The timeouts are short on purpose: a trip is saved only after Booking accepts
 * its snapshot, so an admin request waits for the answer, and a slow Booking must turn into a clear error, not a hang.
 */
@Configuration
public class BookingSeatConfig {

    @Bean
    RestClient bookingSeatRestClient(@Value("${trainbooking.booking-seat.url}") String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
