package com.trainbooking.trainschedule.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.trainschedule.coach.model.CoachRequest;
import com.trainbooking.trainschedule.seat.model.SeatRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class FieldProblemsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // Repeated because the order in which Bean Validation reports the rules of one field is not defined.
    @RepeatedTest(10)
    void aMissingValueReportsOnlyIsRequired() {
        List<ApiError.Detail> details = FieldProblems.fromViolations(validator.validate(new SeatRequest("", 1, "A")), "[0].");

        assertThat(details).containsExactly(new ApiError.Detail("[0].seatNumber", "is required"));
    }

    @Test
    void aPresentButWrongValueReportsTheFormatRule() {
        List<ApiError.Detail> details = FieldProblems.fromViolations(validator.validate(new SeatRequest("1A!", 1, "A")), "");

        assertThat(details).containsExactly(new ApiError.Detail("seatNumber", "must be 1 to 10 letters or digits"));
    }

    @Test
    void everyBrokenFieldIsReportedOnce_withThePrefix() {
        List<ApiError.Detail> details = FieldProblems.fromViolations(validator.validate(new SeatRequest(null, 0, "1")), "[2].");

        assertThat(details).extracting(ApiError.Detail::field).containsExactlyInAnyOrder("[2].seatNumber", "[2].row", "[2].column");
    }

    @Test
    void theJavaNameCoachClassIsReportedUnderItsJsonNameClass() {
        List<ApiError.Detail> details = FieldProblems.fromViolations(validator.validate(new CoachRequest("", "FOURTH", null, -1)), "");

        assertThat(details).extracting(ApiError.Detail::field)
                .containsExactlyInAnyOrder("code", "class", "position", "standingCapacity");
    }

    @Test
    void theJsonNameIsAlsoUsedInsideAListPosition() {
        List<ApiError.Detail> details = FieldProblems.fromViolations(validator.validate(new CoachRequest("A1", "FOURTH", 1, 0)), "[3].");

        assertThat(details).containsExactly(new ApiError.Detail("[3].class", "must be FIRST, SECOND or THIRD"));
    }
}
