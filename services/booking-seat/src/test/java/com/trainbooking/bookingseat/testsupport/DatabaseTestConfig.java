package com.trainbooking.bookingseat.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The beans the database tests replace or add: a clock that can be moved, a fake Payment, and the collector of committed events. */
// @TestConfiguration: A configuration class that exists only for tests; it adds or replaces beans.
@TestConfiguration
public class DatabaseTestConfig {

    // @Bean: Registers the returned object as a Spring bean of the test context.
    @Bean
    // @Primary: When a real bean and this test double both fit an injection point, Spring uses this one.
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
    @Primary
    FakeTicketPort fakeTicket() {
        return new FakeTicketPort();
    }

    @Bean
    @Primary
    FakeNotificationPort fakeNotification() {
        return new FakeNotificationPort();
    }

    @Bean
    @Primary
    FakeTrainSchedulePort fakeTrainSchedule() {
        return new FakeTrainSchedulePort();
    }

    @Bean
    // The Holds helper wraps the real HoldService; the fakes above replace the outside world, and the clock can be moved by hand.
    Holds holds(com.trainbooking.bookingseat.booking.service.HoldService service) {
        return new Holds(service);
    }

    @Bean
    CommittedEvents committedEvents() {
        return new CommittedEvents();
    }
}
