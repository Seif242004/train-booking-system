package com.trainbooking.ticket.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The beans the database tests replace: a clock that can be moved. */
@TestConfiguration
public class DatabaseTestConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
