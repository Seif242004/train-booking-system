package com.trainbooking.bookingseat.booking.service;

import java.security.SecureRandom;

/**
 * Booking references: what a passenger quotes to support (Flow 6, Way B), for example {@code TB-7K2M9Q}. Six characters from an
 * alphabet without the look-alikes (0 and O, 1 and I), chosen at random so that a reference cannot be guessed from the one before.
 */
public final class BookingReferences {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private BookingReferences() {
    }

    public static String next() {
        StringBuilder reference = new StringBuilder("TB-");
        for (int i = 0; i < 6; i++) {
            reference.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return reference.toString();
    }
}
