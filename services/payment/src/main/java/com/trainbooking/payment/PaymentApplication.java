package com.trainbooking.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan: it scans this package and all below it for
// beans (controllers, services, repositories) and configures Spring, Flyway, JDBC and security from the classpath.
// Payment (port 8083) owns the Stripe payments, the webhook check and the refunds; only Booking and Seat and Stripe call it.
@SpringBootApplication
public class PaymentApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentApplication.class, args);
	}

}
