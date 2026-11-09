package com.trainbooking.ticket.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The beans the database tests replace: a clock that can be moved. */
// {@code @TestConfiguration} is a configuration used only by tests. {@code @Primary} makes the bean win over the production Clock
// of TicketConfig when both exist, so the whole application uses the clock the tests control.
@TestConfiguration
public class DatabaseTestConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
