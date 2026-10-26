package com.trainbooking.trainschedule.train.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrainRepositoryTest {

    @Test
    void containsPattern_matchesAnywhere_inUpperCase() {
        assertThat(TrainRepository.containsPattern(" e9 ")).isEqualTo("%E9%");
    }

    @Test
    void containsPattern_treatsLikeWildcardsAsPlainText() {
        // A user typing % or _ must not be able to widen the search; they are escaped for LIKE ... ESCAPE '\'.
        assertThat(TrainRepository.containsPattern("50%_x")).isEqualTo("%50\\%\\_X%");
        assertThat(TrainRepository.containsPattern("a\\b")).isEqualTo("%A\\\\B%");
    }
}
