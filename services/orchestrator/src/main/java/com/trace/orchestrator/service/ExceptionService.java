package com.trace.orchestrator.service;

import com.trace.orchestrator.domain.ExceptionEntity;
import com.trace.orchestrator.dto.ExceptionEventRequest;
import com.trace.orchestrator.dto.ExceptionResponse;
import com.trace.orchestrator.repository.ExceptionRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for exception ingestion (US-01 / TASK-01). Classification
 * (type/severity/root_cause — TASK-02) is out of scope here: rows are
 * persisted with {@code status=DETECTED} and those fields left null.
 */
@Service
public class ExceptionService {

    private static final Logger log = LoggerFactory.getLogger(ExceptionService.class);

    private static final String EXCEPTION_ID_PREFIX = "EXC-";
    private static final int EXCEPTION_ID_MIN_DIGITS = 5;
    static final String STATUS_DETECTED = "DETECTED";

    private final ExceptionRepository exceptionRepository;

    public ExceptionService(ExceptionRepository exceptionRepository) {
        this.exceptionRepository = exceptionRepository;
    }

    /**
     * Persists a new exception from an ingested event and returns its
     * response representation.
     *
     * <p>Only {@code source_system} and the {@code references} (invoice_id /
     * po_id / vendor_id) are persisted on the {@code exceptions} row — the
     * request's {@code event_type}, {@code raw_amount}, {@code currency},
     * and {@code event_timestamp} are validated but have no corresponding
     * column on this table per the DDL (TDD §4.1 / TASK-01 spec); see the
     * scope note surfaced to the caller of this endpoint.
     */
    @Transactional
    public ExceptionResponse ingest(ExceptionEventRequest request) {
        log.debug("Ingesting exception event: source_system={}, event_type={}, invoice_id={}, po_id={}, vendor_id={}",
                request.getSourceSystem(), request.getEventType(), request.getReferences().getInvoiceId(),
                request.getReferences().getPoId(), request.getReferences().getVendorId());

        String exceptionId = generateExceptionId();
        Instant now = Instant.now();

        ExceptionEntity entity = new ExceptionEntity(
                exceptionId,
                request.getSourceSystem(),
                STATUS_DETECTED,
                request.getReferences().getInvoiceId(),
                request.getReferences().getPoId(),
                request.getReferences().getVendorId(),
                now,
                now);

        try {
            exceptionRepository.save(entity);
        } catch (DataAccessException ex) {
            log.warn("Failed to persist exception {}: {}", exceptionId, ex.getMessage());
            throw ex;
        }

        log.info("Persisted exception {} with status={} for source_system={}",
                exceptionId, STATUS_DETECTED, request.getSourceSystem());

        return toResponse(entity);
    }

    private String generateExceptionId() {
        long seq = exceptionRepository.nextExceptionSequence();
        String exceptionId = EXCEPTION_ID_PREFIX + String.format("%0" + EXCEPTION_ID_MIN_DIGITS + "d", seq);
        log.debug("Generated exception_id={} from sequence value {}", exceptionId, seq);
        return exceptionId;
    }

    private ExceptionResponse toResponse(ExceptionEntity entity) {
        String exceptionId = entity.getExceptionId();
        ExceptionResponse.Links links = new ExceptionResponse.Links(
                "/api/v1/exceptions/" + exceptionId,
                "/api/v1/exceptions/" + exceptionId + "/investigation");

        return new ExceptionResponse(
                exceptionId,
                entity.getStatus(),
                entity.getType(),
                entity.getSeverity(),
                entity.getCreatedAt(),
                links);
    }
}
