package com.trainbooking.bookingseat.common;

import jakarta.validation.ConstraintViolation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.validation.FieldError;

/**
 * Turns validation failures into one message per field. A blank value usually breaks two rules at once ("is required"
 * and "must be 1 to 10 letters"), and the order Bean Validation reports them in is not defined. Showing both, or a
 * random one, is confusing, so when a value is missing the "is required" message wins; otherwise the first one is kept.
 */
public final class FieldProblems {

    private static final Set<String> REQUIRED_RULES = Set.of("NotBlank", "NotNull", "NotEmpty");

    private FieldProblems() {
    }

    /** From the field errors of a request body. {@code jsonName} maps a Java property name to the JSON field name. */
    public static List<ApiError.Detail> fromFieldErrors(List<FieldError> errors, UnaryOperator<String> jsonName) {
        Map<String, FieldError> chosen = new LinkedHashMap<>();
        for (FieldError error : errors) {
            chosen.merge(error.getField(), error, (kept, next) -> isRequired(next.getCode()) && !isRequired(kept.getCode()) ? next : kept);
        }
        return chosen.values().stream()
                .map(error -> new ApiError.Detail(jsonName.apply(error.getField()), error.getDefaultMessage()))
                .toList();
    }

    /** From the violations of one object that was validated by hand. {@code prefix} is added to each field name. */
    public static <T> List<ApiError.Detail> fromViolations(Set<ConstraintViolation<T>> violations, String prefix) {
        Map<String, ConstraintViolation<T>> chosen = new LinkedHashMap<>();
        for (ConstraintViolation<T> violation : violations) {
            chosen.merge(violation.getPropertyPath().toString(), violation,
                    (kept, next) -> isRequired(ruleOf(next)) && !isRequired(ruleOf(kept)) ? next : kept);
        }
        return chosen.entrySet().stream()
                .map(entry -> new ApiError.Detail(prefix + jsonName(entry.getKey()), entry.getValue().getMessage()))
                .toList();
    }

    /**
     * Validation reports Java property names, but clients send and read JSON names. {@code class} is a Java keyword, so
     * the record component is {@code coachClass} while the JSON field is {@code class}; errors must name the JSON field.
     */
    public static String jsonName(String property) {
        return "coachClass".equals(property) ? "class" : property;
    }

    private static String ruleOf(ConstraintViolation<?> violation) {
        return violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
    }

    private static boolean isRequired(String rule) {
        return rule != null && REQUIRED_RULES.contains(rule);
    }
}
