package com.trainbooking.bookingseat.testsupport;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The beans the database tests replace or add: a clock that can be moved, a fake Payment, and the collector of committed events. */
@TestConfiguration
public class DatabaseTestConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    FakePaymentPort fakePayment() {
        return new FakePaymentPort();
    }

    @Bean
    Holds holds(com.trainbooking.bookingseat.booking.service.HoldService service) {
        return new Holds(service);
    }

    @Bean
    CommittedEvents committedEvents() {
        return new CommittedEvents();
    }
}
