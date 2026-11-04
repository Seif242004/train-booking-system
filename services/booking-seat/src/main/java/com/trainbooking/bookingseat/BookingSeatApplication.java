package com.trainbooking.bookingseat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Booking and Seat service (port 8082, Oracle schema {@code booking_seat}). This service owns segment availability,
 * the 5-minute holds, bookings, confirmation, cancellation (by the passenger, by an admin, and for a whole trip), the hold-expiry sweeper
  * and the live seat updates. It keeps its own copy of the seat and standing capacity, pushed by Train and Schedule (package {@code
  * snapshot}).
 */
// @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan: Spring starts here, scans all sub-packages of
// com.trainbooking.bookingseat for beans and configures MVC, security, JDBC and Flyway from the classpath and application.properties.
@SpringBootApplication
public class BookingSeatApplication {

	public static void main(String[] args) {
		// Starts the embedded web server and creates all beans. Flyway runs the db/migration scripts before the first request.
		SpringApplication.run(BookingSeatApplication.class, args);
	}

}
