package com.trace.orchestrator.service;

import com.trace.orchestrator.constant.ApiConstants;
import com.trace.orchestrator.constant.ServiceConstants;
import com.trace.orchestrator.domain.ExceptionEntity;
import com.trace.orchestrator.domain.InvoiceEntity;
import com.trace.orchestrator.domain.PurchaseOrderEntity;
import com.trace.orchestrator.dto.ExceptionEventRequest;
import com.trace.orchestrator.dto.ExceptionResponse;
import com.trace.orchestrator.exception.ReferencedRecordNotFoundException;
import com.trace.orchestrator.repository.ExceptionRepository;
import com.trace.orchestrator.repository.InvoiceRepository;
import com.trace.orchestrator.repository.PurchaseOrderRepository;
import com.trace.orchestrator.service.ExceptionClassifier.ClassificationResult;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for exception ingestion and classification (US-01 /
 * TASK-01 + TASK-02). Classification runs synchronously, in the same
 * transaction as ingestion — the client-facing contract (TDD §3.1) returns
 * {@code type}/{@code severity} in the same 201 response that creates the
 * exception.
 *
 * <p>This method persists the row at {@code status=DETECTED} and publishes an
 * {@link ExceptionClassifiedEvent}. It does <em>not</em> investigate:
 * {@link InvestigationTrigger} receives the event only after this
 * transaction commits and starts the investigation on a background worker
 * for HIGH/CRITICAL exceptions (US-01), so the caller's response never waits
 * on the AI service. {@link InvestigationSweeper} covers any that are missed.
 */
@Service
public class ExceptionService {

    private static final Logger log = LoggerFactory.getLogger(ExceptionService.class);

    private final ExceptionRepository exceptionRepository;
    private final InvoiceRepository invoiceRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ExceptionClassifier exceptionClassifier;
    private final ApplicationEventPublisher eventPublisher;

    public ExceptionService(ExceptionRepository exceptionRepository, InvoiceRepository invoiceRepository,
            PurchaseOrderRepository purchaseOrderRepository, ExceptionClassifier exceptionClassifier,
            ApplicationEventPublisher eventPublisher) {
        this.exceptionRepository = exceptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.exceptionClassifier = exceptionClassifier;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Persists a new exception from an ingested event, classifies it, and
     * returns its response representation.
     *
     * <p>Only {@code source_system} and the {@code references} (invoice_id /
     * po_id / vendor_id) are persisted on the {@code exceptions} row — the
     * request's {@code event_type}, {@code raw_amount}, {@code currency},
     * and {@code event_timestamp} are validated but have no corresponding
     * column on this table per the DDL (TDD §4.1 / TASK-01 spec).
     * {@code type}/{@code severity} are not caller-supplied at all (the
     * {@code ExceptionEvent} request schema never accepted them); they're
     * derived here from the linked invoice/PO's actual amounts, never from
     * {@code raw_amount}.
     */
    @Transactional
    public ExceptionResponse ingest(ExceptionEventRequest request) {
        String invoiceId = request.getReferences().getInvoiceId();
        String poId = request.getReferences().getPoId();
        String vendorId = request.getReferences().getVendorId();

        log.debug("Ingesting exception event: source_system={}, event_type={}, invoice_id={}, po_id={}, vendor_id={}",
                request.getSourceSystem(), request.getEventType(), invoiceId, poId, vendorId);

        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ReferencedRecordNotFoundException(
                        "Referenced invoice does not exist: " + invoiceId));
        PurchaseOrderEntity purchaseOrder = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new ReferencedRecordNotFoundException(
                        "Referenced purchase order does not exist: " + poId));

        ClassificationResult classification = exceptionClassifier.classify(invoice, purchaseOrder);

        String exceptionId = generateExceptionId();
        Instant now = Instant.now();

        ExceptionEntity entity = new ExceptionEntity(
                exceptionId,
                request.getSourceSystem(),
                classification.type(),
                classification.severity(),
                ServiceConstants.STATUS_DETECTED,
                invoiceId,
                poId,
                vendorId,
                now,
                now);

        try {
            exceptionRepository.save(entity);
        } catch (DataAccessException ex) {
            log.warn("Failed to persist exception {}: {}", exceptionId, ex.getMessage());
            throw ex;
        }

        log.info("Persisted exception {} with status={}, type={}, severity={} (deviation={}%) for source_system={}",
                exceptionId, ServiceConstants.STATUS_DETECTED, classification.type(), classification.severity(),
                classification.deviationPct(), request.getSourceSystem());

        // Delivered only after this transaction commits (see InvestigationTrigger). The trace id is captured
        // here because the request-scoped MDC doesn't follow the investigation to its worker thread.
        eventPublisher.publishEvent(new ExceptionClassifiedEvent(exceptionId, classification.severity(),
                MDC.get(ApiConstants.TRACE_ID_MDC_KEY)));

        return toResponse(entity);
    }

    private String generateExceptionId() {
        long seq = exceptionRepository.nextExceptionSequence();
        String exceptionId = ServiceConstants.EXCEPTION_ID_PREFIX + String.format("%0" + ServiceConstants.EXCEPTION_ID_MIN_DIGITS + "d", seq);
        log.debug("Generated exception_id={} from sequence value {}", exceptionId, seq);
        return exceptionId;
    }

    private ExceptionResponse toResponse(ExceptionEntity entity) {
        String exceptionId = entity.getExceptionId();
        ExceptionResponse.Links links = new ExceptionResponse.Links(
                ApiConstants.EXCEPTIONS_BASE_PATH + "/" + exceptionId,
                ApiConstants.EXCEPTIONS_BASE_PATH + "/" + exceptionId + ApiConstants.INVESTIGATION_LINK_SUFFIX);

        return new ExceptionResponse(
                exceptionId,
                entity.getStatus(),
                entity.getType(),
                entity.getSeverity(),
                entity.getCreatedAt(),
                links);
    }
}
