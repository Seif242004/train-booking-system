package com.trainbooking.payment.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Where the database tests connect: the throwaway {@code payment_test} schema, never the dev schema. The password
 * comes from the PAYMENT_TEST_DB_PASSWORD environment variable or, when running from the service folder, from its
 * git-ignored {@code .env} file. Without a password the database tests are skipped (see {@link RequiresTestDatabase}).
 */
public final class TestDatabase {

    private static final Map<String, String> DOT_ENV = readDotEnv();

    private TestDatabase() {
    }

    public static String url() {
        return setting("PAYMENT_TEST_DB_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1");
    }

    public static String user() {
        return setting("PAYMENT_TEST_DB_USER", "payment_test");
    }

    /** Empty when the database tests are not configured. */
    public static String password() {
        return setting("PAYMENT_TEST_DB_PASSWORD", "");
    }

    private static String setting(String name, String fallback) {
        String fromEnvironment = System.getenv(name);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        String fromFile = DOT_ENV.get(name);
        return fromFile != null && !fromFile.isBlank() ? fromFile : fallback;
    }

    private static Map<String, String> readDotEnv() {
        Map<String, String> values = new HashMap<>();
        Path file = Path.of(".env");
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                int equals = line.indexOf('=');
                if (equals > 0 && !line.startsWith("#")) {
                    values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
                }
            }
        } catch (IOException e) {
            // An unreadable .env is the same as no .env: the database tests are skipped.
        }
        return values;
    }
}
