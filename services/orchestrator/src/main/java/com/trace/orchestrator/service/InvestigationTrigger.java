package com.trace.orchestrator.service;

import com.trace.orchestrator.constant.ServiceConstants;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts an investigation as soon as a HIGH/CRITICAL exception is committed
 * (US-01: "automatically triggered within 5 seconds"), and hands the work to
 * the bounded investigation pool so the client's {@code 201} never waits on
 * the AI service.
 *
 * <p>The listener runs <em>after</em> the ingestion transaction commits, so
 * the row is visible to the worker and a rolled-back ingestion never triggers
 * anything. It must never throw: an exception here would surface to the
 * ingestion caller even though the exception was created successfully. If the
 * pool rejects the task the exception simply stays {@code DETECTED} and
 * {@link InvestigationSweeper} picks it up.
 */
@Component
public class InvestigationTrigger {

    private static final Logger log = LoggerFactory.getLogger(InvestigationTrigger.class);

    private final InvestigationService investigationService;
    private final Executor executor;

    public InvestigationTrigger(InvestigationService investigationService,
            @Qualifier(ServiceConstants.INVESTIGATION_EXECUTOR_BEAN) Executor executor) {
        this.investigationService = investigationService;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExceptionClassified(ExceptionClassifiedEvent event) {
        if (!ServiceConstants.AUTO_INVESTIGATE_SEVERITIES.contains(event.severity())) {
            log.debug("Exception {} has severity {} — not auto-investigated", event.exceptionId(), event.severity());
            return;
        }
        dispatch(event.exceptionId(), event.traceId());
    }

    /**
     * Queues an investigation on the worker pool. Never throws.
     *
     * @return {@code true} if the task was accepted, {@code false} if it could not be queued
     */
    boolean dispatch(String exceptionId, String traceId) {
        try {
            executor.execute(() -> investigationService.investigate(exceptionId, traceId));
            return true;
        } catch (RuntimeException ex) {
            log.warn("Could not queue investigation for exception {} — it stays DETECTED for the sweeper: {}",
                    exceptionId, ex.getMessage());
            return false;
        }
    }
}
