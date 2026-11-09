package com.trainbooking.ticket.testsupport;

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
// Meta-annotations: {@code @Target} lets it be put on classes only; {@code @Retention(RUNTIME)} keeps it visible to JUnit at run time;
// {@code @ExtendWith} adds the JUnit extension below, which decides before each class whether it runs.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresTestDatabase.Condition.class)
public @interface RequiresTestDatabase {

    // The extension: if no password is set it returns "disabled" with a reason, and JUnit reports the tests as skipped (not passed).
    class Condition implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (TestDatabase.password().isEmpty()) {
                return ConditionEvaluationResult.disabled(
                        "SKIPPED database tests: TICKET_TEST_DB_PASSWORD is not set (see services/ticket/README.md)");
            }
            return ConditionEvaluationResult.enabled("ticket_test credentials found");
        }
    }
}
