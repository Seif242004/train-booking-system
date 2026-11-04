package com.trainbooking.trainschedule.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The time zone of the railway. Trip times are entered and shown as local date-times in this zone (Egypt observes
 * daylight saving time, so the offset is not constant) and are stored in the database with their offset.
 */
// @Configuration: declares beans by hand. @Bean: each method's result is shared by the whole application.
@Configuration
public class TimeConfig {

    /** The railway zone, from the property {@code trainbooking.timezone} (default Africa/Cairo). Used to read and show trip times. */
    @Bean
    ZoneId serviceZone(@Value("${trainbooking.timezone}") String zone) {
        return ZoneId.of(zone);
    }

    /** The clock the services read "now" from, so that tests can fix it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
