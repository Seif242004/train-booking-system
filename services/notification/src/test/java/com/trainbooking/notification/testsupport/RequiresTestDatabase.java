package com.trainbooking.notification.testsupport;

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
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresTestDatabase.Condition.class)
public @interface RequiresTestDatabase {

    class Condition implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (TestDatabase.password().isEmpty()) {
                return ConditionEvaluationResult.disabled(
                        "SKIPPED database tests: NOTIFICATION_TEST_DB_PASSWORD is not set (see services/notification/README.md)");
            }
            return ConditionEvaluationResult.enabled("notification_test credentials found");
        }
    }
}
