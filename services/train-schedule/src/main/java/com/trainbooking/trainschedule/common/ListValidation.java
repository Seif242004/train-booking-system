package com.trainbooking.trainschedule.common;

import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a request body that is a JSON list (the seats of a coach, the fares of a route). Every problem is collected
 * and reported together, each one naming the position of the item in the list, for example {@code [2].amount}, so
 * the admin can fix the whole list in one go. Nothing is saved if anything is wrong: the caller stops on the exception.
 */
public final class ListValidation {

    private ListValidation() {
    }

    /**
     * @param listField name used for problems about the list as a whole (empty or too long)
     * @param max       the most items one request may carry
     * @throws RequestValidationException if the list or any item is not valid
     */
    public static <T> void require(Validator validator, List<T> items, String listField, int max) {
        List<ApiError.Detail> problems = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            problems.add(new ApiError.Detail(listField, "at least one item is required"));
        } else if (items.size() > max) {
            problems.add(new ApiError.Detail(listField, "at most " + max + " items per request"));
        } else {
            for (int i = 0; i < items.size(); i++) {
                T item = items.get(i);
                if (item == null) {
                    problems.add(new ApiError.Detail("[" + i + "]", "is required"));
                    continue;
                }
                problems.addAll(FieldProblems.fromViolations(validator.validate(item), "[" + i + "]."));
            }
        }
        if (!problems.isEmpty()) {
            throw new RequestValidationException(problems);
        }
    }
}
