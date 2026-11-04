package com.trainbooking.trainschedule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Train &amp; Schedule service (port 8081, Oracle schema {@code train_schedule}).
 *
 * <p>This service owns the timetable data: stations, trains, coaches, seats, standing capacity, routes, fares and trips.
 * Admins edit them through {@code /admin/**}; passengers only search trips ({@code /trips}, {@code /stations}).
 * Every trip it saves is also pushed to Booking &amp; Seat, which keeps its own copy (see the {@code trip.service} package).
 * It is called by the API Gateway (with the user's JWT) and by Booking &amp; Seat (with the internal API key).
 */
// @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan. It tells Spring Boot to scan this
// package and all sub-packages for beans (controllers, services, repositories) and to configure itself from the classpath
// (web server, JDBC pool, Flyway migrations, security, ...).
@SpringBootApplication
public class TrainScheduleApplication {

	/** Starts the embedded web server and the Spring application context. */
	public static void main(String[] args) {
		SpringApplication.run(TrainScheduleApplication.class, args);
	}

}
