package com.trainbooking.notification.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The beans the database tests replace: a clock that can be moved and a fake mail server. */
@TestConfiguration
public class DatabaseTestConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    FakeEmailSender fakeMailServer() {
        return new FakeEmailSender();
    }
}
