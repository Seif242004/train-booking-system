package com.trainbooking.payment.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The beans the database tests replace: a clock that can be moved, a fake Stripe and a fake Booking. */
@TestConfiguration
public class DatabaseTestConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    FakeStripeGateway fakeStripe() {
        return new FakeStripeGateway();
    }

    @Bean
    @Primary
    FakeBookingNotifier fakeBooking() {
        return new FakeBookingNotifier();
    }
}
