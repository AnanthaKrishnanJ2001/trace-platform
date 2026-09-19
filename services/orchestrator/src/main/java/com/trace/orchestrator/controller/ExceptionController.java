package com.trace.orchestrator.controller;

import com.trace.orchestrator.constant.ApiConstants;
import com.trace.orchestrator.dto.ExceptionEventRequest;
import com.trace.orchestrator.dto.ExceptionResponse;
import com.trace.orchestrator.service.ExceptionIngestionService;
import com.trace.orchestrator.service.ExceptionIngestionService.IngestionResult;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exception ingestion endpoint (US-01 / TASK-01) — mapping and delegation
 * only; see {@link ExceptionIngestionService} for the idempotency guard and
 * {@link com.trace.orchestrator.service.ExceptionService} for the actual logic.
 */
@RestController
@RequestMapping(ApiConstants.EXCEPTIONS_BASE_PATH)
public class ExceptionController {

    private static final Logger log = LoggerFactory.getLogger(ExceptionController.class);

    private final ExceptionIngestionService exceptionIngestionService;

    public ExceptionController(ExceptionIngestionService exceptionIngestionService) {
        this.exceptionIngestionService = exceptionIngestionService;
    }

    @PostMapping(ApiConstants.EXCEPTIONS_CREATE_PATH)
    public ResponseEntity<ExceptionResponse> createException(
            @RequestHeader(name = ApiConstants.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ExceptionEventRequest request) {
        log.info("Received exception ingestion request: source_system={}, event_type={}, invoice_id={}, idempotent={}",
                request.getSourceSystem(), request.getEventType(),
                request.getReferences() != null ? request.getReferences().getInvoiceId() : null,
                idempotencyKey != null);

        IngestionResult result = exceptionIngestionService.ingest(idempotencyKey, request);
        ExceptionResponse response = result.response();

        log.info("Exception ingestion request handled: exception_id={}, status={}, httpStatus={}, replayed={}",
                response.getExceptionId(), response.getStatus(), HttpStatus.CREATED.value(), result.replayed());

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            builder.header(ApiConstants.IDEMPOTENT_REPLAYED_HEADER, ApiConstants.IDEMPOTENT_REPLAYED_VALUE);
        }
        return builder.body(response);
    }
}
