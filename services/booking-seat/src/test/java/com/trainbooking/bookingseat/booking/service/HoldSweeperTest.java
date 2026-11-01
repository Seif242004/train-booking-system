package com.trainbooking.bookingseat.booking.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class HoldSweeperTest {

    @Test
    void theSweepRunsEverySecondByDefault_withAFixedDelay_thatCanBeConfigured() throws Exception {
        Method sweep = HoldSweeper.class.getMethod("sweep");

        Scheduled scheduled = sweep.getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${trainbooking.sweeper.fixed-delay-ms:1000}");
        assertThat(scheduled.fixedRate()).isEqualTo(-1); // fixedDelay: a slow sweep never overlaps the next one
    }

    @Test
    void anExceptionInASweep_neverEscapes_soTheScheduleKeepsRunning() {
        ExpiryService expiry = mock(ExpiryService.class);
        when(expiry.sweep()).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> new HoldSweeper(expiry).sweep()).doesNotThrowAnyException();
        verify(expiry).sweep();
    }
}
