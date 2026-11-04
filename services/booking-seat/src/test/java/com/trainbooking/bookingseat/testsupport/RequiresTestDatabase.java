package com.trainbooking.bookingseat.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Marks a test class that needs the Oracle test schema. When no password is configured the whole class is skipped, and
 * Gradle reports it as SKIPPED (see the test logging in build.gradle) instead of failing, so a missing database is
 * never mistaken for a pass.
 */
// @Target: Meta-annotations: @Target says where this annotation may be put (on a class), @Retention(RUNTIME) keeps it at run time so
// JUnit can read it.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
// @ExtendWith: Attaches a JUnit 5 extension; the Condition below decides whether the class runs.
@ExtendWith(RequiresTestDatabase.Condition.class)
public @interface RequiresTestDatabase {

    // JUnit 5 ExecutionCondition: asked before the class runs; "disabled" turns the class into SKIPPED.
    class Condition implements ExecutionCondition {

        // @Override: This method replaces the one of the parent class or interface.
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (TestDatabase.password().isEmpty()) {
                return ConditionEvaluationResult.disabled(
                        "SKIPPED database tests: BOOKING_SEAT_TEST_DB_PASSWORD is not set (see services/booking-seat/README.md)");
            }
            return ConditionEvaluationResult.enabled("booking_seat_test credentials found");
        }
    }
}
