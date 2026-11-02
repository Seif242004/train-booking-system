package com.trainbooking.notification.common;

import com.trainbooking.notification.notification.service.EmailTemplates;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The clock (so retries can be tested by moving time), the time zone of the times in the emails, and the scheduler. */
@Configuration
@EnableScheduling
public class NotificationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EmailTemplates emailTemplates(@Value("${trainbooking.timezone}") String timezone) {
        return new EmailTemplates(ZoneId.of(timezone));
    }
}
