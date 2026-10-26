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
@Configuration
public class TimeConfig {

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
