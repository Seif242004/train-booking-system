package com.trainbooking.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Notification service (port 8085). Notification owns the emails of the system (ticket, cancellation, refund).
 * Users never call it: Booking and Seat does, with the shared API key, from its outbox (see docs/ARCHITECTURE.md).
 * {@code @SpringBootApplication} switches on component scanning of this package and its sub-packages, auto-configuration
 * (database, Flyway, Spring MVC, Spring Mail, security) and marks this class as the configuration root.
 */
@SpringBootApplication
public class NotificationApplication {

	// Starts the embedded web server and the Spring context. The scheduler is enabled in NotificationConfig.
	public static void main(String[] args) {
		SpringApplication.run(NotificationApplication.class, args);
	}

}
