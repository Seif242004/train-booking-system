package com.trainbooking.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Ticket service (port 8084, Oracle schema {@code ticket}). It owns the PDF ticket of a booking with its QR code, and
 * the invalidation of that ticket when the booking is cancelled (CLAUDE.md, "Services"). Nobody calls it from a browser: only Booking and
 * Seat does, with the shared internal API key.
 *
 * <p>{@code @SpringBootApplication} turns on three things at once: configuration (this class is a configuration class), auto-configuration
 * (Spring Boot builds the web server, the DataSource, Flyway and so on from the libraries on the classpath and from
 * {@code application.properties}) and component scanning (every class below this package marked {@code @Component}, {@code @Service},
 * {@code @Repository}, {@code @RestController} and so on becomes a bean).
 */
@SpringBootApplication
public class TicketApplication {

	// Starts the embedded web server, runs the Flyway migration of the ticket schema and wires all the beans.
	public static void main(String[] args) {
		SpringApplication.run(TicketApplication.class, args);
	}

}
