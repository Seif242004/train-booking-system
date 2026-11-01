package com.trainbooking.payment.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The clock the service reads "now" from, so that retries and backoff can be tested by moving time. Also switches on the schedulers. */
@Configuration
@EnableScheduling
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
