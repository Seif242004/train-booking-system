package com.trainbooking.notification.common;

import com.trainbooking.notification.notification.service.EmailTemplates;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The clock (so retries can be tested by moving time), the time zone of the times in the emails, and the scheduler. */
// @Configuration: this class declares beans. @EnableScheduling turns on @Scheduled methods (EmailScheduler); without it they never run.
@Configuration
@EnableScheduling
public class NotificationConfig {

    @Bean
    // @Bean: the returned object is registered in the Spring context. Here a UTC system clock; tests replace it with a movable one so
    // the retry backoff can be tested without waiting.
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    // Reads trainbooking.timezone (env TIMEZONE, default Africa/Cairo) with @Value, so times in the emails are shown in local time.
    EmailTemplates emailTemplates(@Value("${trainbooking.timezone}") String timezone) {
        return new EmailTemplates(ZoneId.of(timezone));
    }
}
