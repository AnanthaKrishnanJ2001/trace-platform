package com.trace.orchestrator.service;

import com.trace.orchestrator.config.InvestigationProperties;
import com.trace.orchestrator.constant.ServiceConstants;
import com.trace.orchestrator.repository.ExceptionRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net behind {@link InvestigationTrigger}: the after-commit trigger is
 * the fast path, but it can be lost — the app restarts between commit and
 * dispatch, the worker pool is full, or a worker dies mid-investigation.
 * Periodically this job:
 * <ol>
 *   <li>marks exceptions stuck in {@code INVESTIGATING} for longer than
 *       {@code staleAfter} as {@code INVESTIGATION_UNAVAILABLE} (nothing else
 *       would ever move them), and</li>
 *   <li>dispatches HIGH/CRITICAL exceptions still {@code DETECTED} after
 *       {@code sweepGrace}.</li>
 * </ol>
 * It is safe with several app instances and alongside the trigger: every
 * change goes through a guarded UPDATE, so the database lets exactly one
 * caller act on a given row.
 *
 * <p>The first run is delayed by one interval so start-up (and test contexts)
 * isn't immediately followed by database writes.
 */
@Component
public class InvestigationSweeper {

    private static final Logger log = LoggerFactory.getLogger(InvestigationSweeper.class);

    private final ExceptionRepository exceptionRepository;
    private final InvestigationTrigger investigationTrigger;
    private final InvestigationProperties properties;

    public InvestigationSweeper(ExceptionRepository exceptionRepository, InvestigationTrigger investigationTrigger,
            InvestigationProperties properties) {
        this.exceptionRepository = exceptionRepository;
        this.investigationTrigger = investigationTrigger;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{@investigationProperties.sweepInterval.toMillis()}",
            initialDelayString = "#{@investigationProperties.sweepInterval.toMillis()}")
    public void sweep() {
        try {
            failStaleInvestigations();
            dispatchMissedInvestigations();
        } catch (RuntimeException ex) {
            // A failed sweep must not cancel the schedule; the next tick retries.
            log.error("Investigation sweep failed", ex);
        }
    }

    private void failStaleInvestigations() {
        Instant now = Instant.now();
        List<String> stale = exceptionRepository.findIdsByStatusUpdatedBefore(ServiceConstants.STATUS_INVESTIGATING,
                now.minus(properties.getStaleAfter()), PageRequest.of(0, properties.getSweepBatchSize()));
        for (String exceptionId : stale) {
            int updated = exceptionRepository.transitionStatus(exceptionId, ServiceConstants.STATUS_INVESTIGATING,
                    ServiceConstants.STATUS_INVESTIGATION_UNAVAILABLE, now);
            if (updated == 1) {
                log.warn("Exception {} was stuck INVESTIGATING for over {} — marked {}", exceptionId,
                        properties.getStaleAfter(), ServiceConstants.STATUS_INVESTIGATION_UNAVAILABLE);
            }
        }
    }

    private void dispatchMissedInvestigations() {
        List<String> missed = exceptionRepository.findIdsByStatusAndSeverityUpdatedBefore(
                ServiceConstants.STATUS_DETECTED, ServiceConstants.AUTO_INVESTIGATE_SEVERITIES,
                Instant.now().minus(properties.getSweepGrace()), PageRequest.of(0, properties.getSweepBatchSize()));
        for (String exceptionId : missed) {
            log.info("Sweeper found exception {} still DETECTED — dispatching investigation", exceptionId);
            // No originating request here, so no trace id: InvestigationService generates one.
            if (!investigationTrigger.dispatch(exceptionId, null)) {
                break; // pool is full; the rest wait for the next sweep
            }
        }
    }
}
