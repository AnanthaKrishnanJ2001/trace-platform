package com.trace.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link InvestigationTrigger}: only HIGH/CRITICAL are auto-investigated, the
 * work is handed to the executor (not run inline), and a full pool can never
 * make the listener throw into the ingestion caller.
 */
@ExtendWith(MockitoExtension.class)
class InvestigationTriggerTest {

    @Mock
    private InvestigationService investigationService;

    @ParameterizedTest
    @ValueSource(strings = {"HIGH", "CRITICAL"})
    void onExceptionClassified_highAndCritical_dispatchWithTraceId(String severity) {
        InvestigationTrigger trigger = new InvestigationTrigger(investigationService, Runnable::run);

        trigger.onExceptionClassified(new ExceptionClassifiedEvent("EXC-00011", severity, "trace-1"));

        verify(investigationService).investigate("EXC-00011", "trace-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOW", "MEDIUM"})
    void onExceptionClassified_lowAndMedium_areNotAutoInvestigated(String severity) {
        InvestigationTrigger trigger = new InvestigationTrigger(investigationService, Runnable::run);

        trigger.onExceptionClassified(new ExceptionClassifiedEvent("EXC-00011", severity, "trace-1"));

        verifyNoInteractions(investigationService);
    }

    @Test
    void onExceptionClassified_handsWorkToExecutor_insteadOfRunningInline() {
        List<Runnable> queued = new ArrayList<>();
        InvestigationTrigger trigger = new InvestigationTrigger(investigationService, queued::add);

        trigger.onExceptionClassified(new ExceptionClassifiedEvent("EXC-00011", "HIGH", "trace-1"));

        // Nothing has run yet: the request thread returned as soon as the task was queued.
        verifyNoInteractions(investigationService);
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        verify(investigationService).investigate("EXC-00011", "trace-1");
    }

    @Test
    void onExceptionClassified_whenPoolRejects_doesNotThrow() {
        InvestigationTrigger trigger = new InvestigationTrigger(investigationService, task -> {
            throw new RejectedExecutionException("queue full");
        });

        assertThatCode(() -> trigger.onExceptionClassified(new ExceptionClassifiedEvent("EXC-00011", "HIGH", "t")))
                .doesNotThrowAnyException();
    }

    @Test
    void dispatch_reportsWhetherTaskWasQueued() {
        InvestigationTrigger accepting = new InvestigationTrigger(investigationService, Runnable::run);
        InvestigationTrigger rejecting = new InvestigationTrigger(investigationService, task -> {
            throw new RejectedExecutionException("queue full");
        });

        assertThat(accepting.dispatch("EXC-00011", null)).isTrue();
        assertThat(rejecting.dispatch("EXC-00011", null)).isFalse();
    }
}
