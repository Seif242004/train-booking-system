package com.trainbooking.bookingseat.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock the service reads "now" from, so that the expiry of holds can be tested by moving time. */
// @Configuration: this class declares beans. @Bean below registers the Clock under its method name; services receive it by constructor
// injection. Tests replace it with a MutableClock, so "5 minutes later" needs no sleeping. All stored times are UTC instants.
@Configuration
public class TimeConfig {

    @Bean
    // Package-private on purpose: Spring can still call it, and no other package needs it.
    Clock clock() {
        return Clock.systemUTC();
    }
}
