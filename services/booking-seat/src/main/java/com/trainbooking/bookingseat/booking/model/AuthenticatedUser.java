package com.trainbooking.bookingseat.booking.model;

import org.springframework.security.oauth2.jwt.Jwt;

/** Who is calling: the Keycloak subject, and the e-mail and name from the token (kept as plain values on the booking). */
public record AuthenticatedUser(String id, String email, String name) {

    // Reads the claims of the verified Keycloak token: sub (stable user id), email and name. If there is no "name" claim the Keycloak
    // username is used. These values are copied onto the booking as plain text, so Ticket and Notification need no user table.
    public static AuthenticatedUser of(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        return new AuthenticatedUser(jwt.getSubject(), jwt.getClaimAsString("email"), name != null ? name : jwt.getClaimAsString("preferred_username"));
    }
}
