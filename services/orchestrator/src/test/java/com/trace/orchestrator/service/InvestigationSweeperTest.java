package com.trace.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trace.orchestrator.config.InvestigationProperties;
import com.trace.orchestrator.repository.ExceptionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/** {@link InvestigationSweeper}: the safety net behind the after-commit trigger. */
@ExtendWith(MockitoExtension.class)
class InvestigationSweeperTest {

    @Mock
    private ExceptionRepository exceptionRepository;

    @Mock
    private InvestigationTrigger investigationTrigger;

    private InvestigationSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new InvestigationSweeper(exceptionRepository, investigationTrigger, new InvestigationProperties());
    }

    @Test
    void sweep_dispatchesHighAndCriticalExceptionsStillDetected_withoutATraceId() {
        noStale();
        when(exceptionRepository.findIdsByStatusAndSeverityUpdatedBefore(eq("DETECTED"),
                eq(Set.of("HIGH", "CRITICAL")), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of("EXC-00001", "EXC-00002"));
        when(investigationTrigger.dispatch(any(), isNull())).thenReturn(true);

        sweeper.sweep();

        verify(investigationTrigger).dispatch("EXC-00001", null);
        verify(investigationTrigger).dispatch("EXC-00002", null);
    }

    @Test
    void sweep_stopsDispatchingOnceThePoolIsFull() {
        noStale();
        when(exceptionRepository.findIdsByStatusAndSeverityUpdatedBefore(any(), any(), any(Instant.class),
                any(Pageable.class))).thenReturn(List.of("EXC-00001", "EXC-00002", "EXC-00003"));
        when(investigationTrigger.dispatch("EXC-00001", null)).thenReturn(true);
        when(investigationTrigger.dispatch("EXC-00002", null)).thenReturn(false);

        sweeper.sweep();

        verify(investigationTrigger, never()).dispatch("EXC-00003", null);
    }

    @Test
    void sweep_marksExceptionsStuckInvestigatingAsUnavailable() {
        when(exceptionRepository.findIdsByStatusUpdatedBefore(eq("INVESTIGATING"), any(Instant.class),
                any(Pageable.class))).thenReturn(List.of("EXC-00007"));
        when(exceptionRepository.transitionStatus(eq("EXC-00007"), eq("INVESTIGATING"),
                eq("INVESTIGATION_UNAVAILABLE"), any(Instant.class))).thenReturn(1);
        when(exceptionRepository.findIdsByStatusAndSeverityUpdatedBefore(any(), any(), any(Instant.class),
                any(Pageable.class))).thenReturn(List.of());

        sweeper.sweep();

        verify(exceptionRepository).transitionStatus(eq("EXC-00007"), eq("INVESTIGATING"),
                eq("INVESTIGATION_UNAVAILABLE"), any(Instant.class));
    }

    @Test
    void sweep_neverPropagatesFailures_soTheScheduleSurvives() {
        when(exceptionRepository.findIdsByStatusUpdatedBefore(any(), any(Instant.class), any(Pageable.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> sweeper.sweep()).doesNotThrowAnyException();
    }

    private void noStale() {
        when(exceptionRepository.findIdsByStatusUpdatedBefore(eq("INVESTIGATING"), any(Instant.class),
                any(Pageable.class))).thenReturn(List.of());
    }
}
