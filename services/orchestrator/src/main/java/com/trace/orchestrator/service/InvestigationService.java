package com.trace.orchestrator.service;

import com.trace.orchestrator.client.InvestigationClient;
import com.trace.orchestrator.config.InvestigationProperties;
import com.trace.orchestrator.constant.ApiConstants;
import com.trace.orchestrator.constant.ServiceConstants;
import com.trace.orchestrator.domain.ExceptionEntity;
import com.trace.orchestrator.dto.InvestigationRequest;
import com.trace.orchestrator.dto.InvestigationResult;
import com.trace.orchestrator.exception.InvestigationUnavailableException;
import com.trace.orchestrator.repository.ExceptionRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Drives one exception through an AI investigation (US-01 auto-trigger,
 * TASK-19/20 client side).
 *
 * <p>Deliberately <em>not</em> transactional: the Engine call can take up to
 * 8 seconds and must not hold a DB connection or row lock. Instead each
 * status change is its own short, guarded UPDATE on
 * {@link ExceptionRepository}:
 * <ol>
 *   <li><b>Claim</b> — {@code DETECTED -> INVESTIGATING}, only if still
 *       {@code DETECTED}. The database picks a single winner when the
 *       after-commit trigger and the sweeper race; the loser gets 0 rows
 *       and returns without calling the Engine.</li>
 *   <li><b>Call</b> the Engine (JWT + {@code X-Trace-Id}, through the
 *       {@code investigationService} breaker).</li>
 *   <li><b>Record the outcome</b> — {@code INVESTIGATED},
 *       {@code NEEDS_HUMAN_REVIEW} or {@code INVESTIGATION_UNAVAILABLE}.
 *       Whatever happens after the claim, the row never stays
 *       {@code INVESTIGATING}.</li>
 * </ol>
 */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    private final ExceptionRepository exceptionRepository;
    private final InvestigationClient investigationClient;
    private final InvestigationProperties properties;

    public InvestigationService(ExceptionRepository exceptionRepository, InvestigationClient investigationClient,
            InvestigationProperties properties) {
        this.exceptionRepository = exceptionRepository;
        this.investigationClient = investigationClient;
        this.properties = properties;
    }

    /**
     * @param traceId the originating request's trace id; {@code null} when no
     *                request exists (e.g. the sweeper), in which case a new one
     *                is generated so the Engine's logs can still be joined
     */
    public void investigate(String exceptionId, String traceId) {
        String effectiveTraceId = traceId != null ? traceId : UUID.randomUUID().toString();
        MDC.put(ApiConstants.TRACE_ID_MDC_KEY, effectiveTraceId);
        try {
            run(exceptionId, effectiveTraceId);
        } finally {
            MDC.remove(ApiConstants.TRACE_ID_MDC_KEY);
        }
    }

    private void run(String exceptionId, String traceId) {
        int claimed = exceptionRepository.transitionStatus(exceptionId, ServiceConstants.STATUS_DETECTED,
                ServiceConstants.STATUS_INVESTIGATING, Instant.now());
        if (claimed == 0) {
            log.debug("Exception {} is no longer DETECTED — another worker owns it, skipping", exceptionId);
            return;
        }
        log.info("Claimed exception {} for investigation", exceptionId);

        try {
            ExceptionEntity exception = exceptionRepository.findById(exceptionId).orElseThrow(
                    () -> new IllegalStateException("Exception vanished after being claimed: " + exceptionId));
            InvestigationResult result = investigationClient.investigate(buildRequest(exception), traceId);
            recordOutcome(exceptionId, result);
        } catch (InvestigationUnavailableException ex) {
            log.warn("AI service unavailable for exception {}: {}", exceptionId, ex.getMessage());
            markUnavailable(exceptionId);
        } catch (RuntimeException ex) {
            log.error("Unexpected failure investigating exception {}", exceptionId, ex);
            markUnavailable(exceptionId);
        }
    }

    private InvestigationRequest buildRequest(ExceptionEntity exception) {
        return new InvestigationRequest(
                ServiceConstants.INVESTIGATION_ID_PREFIX + UUID.randomUUID(),
                exception.getExceptionId(),
                exception.getType(),
                new InvestigationRequest.References(exception.getInvoiceId(), exception.getPoId(),
                        exception.getVendorId()),
                new InvestigationRequest.Context(properties.getConfidenceThreshold()));
    }

    /**
     * FAILED means the Engine produced nothing usable, so it is treated like
     * an outage. Anything else with a valid, sufficiently confident root
     * cause is INVESTIGATED; a missing/invalid root cause or a confidence
     * below the threshold needs a human (US-03).
     */
    private void recordOutcome(String exceptionId, InvestigationResult result) {
        if (ServiceConstants.INVESTIGATION_RESULT_FAILED.equals(result.status())) {
            log.warn("AI service reported a FAILED investigation for exception {}", exceptionId);
            markUnavailable(exceptionId);
            return;
        }

        InvestigationResult.RootCause rootCause = result.rootCause();
        Short confidence = validConfidence(rootCause);
        String label = rootCause == null ? null : truncate(rootCause.rootCauseLabel());

        boolean trusted = label != null && confidence != null && confidence >= properties.getConfidenceThreshold();
        String finalStatus = trusted ? ServiceConstants.STATUS_INVESTIGATED
                : ServiceConstants.STATUS_NEEDS_HUMAN_REVIEW;

        int updated = exceptionRepository.recordInvestigationOutcome(exceptionId,
                ServiceConstants.STATUS_INVESTIGATING, finalStatus, label, confidence, Instant.now());
        if (updated == 0) {
            log.warn("Exception {} left INVESTIGATING while its result was in flight — result discarded",
                    exceptionId);
            return;
        }
        log.info("Exception {} -> {} (root_cause={}, confidence={}, engine_status={})",
                exceptionId, finalStatus, label, confidence, result.status());
    }

    private void markUnavailable(String exceptionId) {
        int updated = exceptionRepository.transitionStatus(exceptionId, ServiceConstants.STATUS_INVESTIGATING,
                ServiceConstants.STATUS_INVESTIGATION_UNAVAILABLE, Instant.now());
        if (updated == 0) {
            log.warn("Exception {} was no longer INVESTIGATING when marking it unavailable", exceptionId);
        } else {
            log.info("Exception {} -> {}", exceptionId, ServiceConstants.STATUS_INVESTIGATION_UNAVAILABLE);
        }
    }

    /** The DB rejects confidence outside 0-100, so an out-of-range value is treated as absent. */
    private static Short validConfidence(InvestigationResult.RootCause rootCause) {
        if (rootCause == null || rootCause.confidence() == null) {
            return null;
        }
        int value = rootCause.confidence();
        boolean inRange = value >= ServiceConstants.MIN_CONFIDENCE && value <= ServiceConstants.MAX_CONFIDENCE;
        return inRange ? (short) value : null;
    }

    private static String truncate(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        int max = ServiceConstants.ROOT_CAUSE_LABEL_MAX_LENGTH;
        return label.length() <= max ? label : label.substring(0, max);
    }
}
