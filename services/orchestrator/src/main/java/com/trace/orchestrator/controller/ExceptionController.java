package com.trace.orchestrator.controller;

import com.trace.orchestrator.dto.ExceptionEventRequest;
import com.trace.orchestrator.dto.ExceptionResponse;
import com.trace.orchestrator.service.ExceptionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exception ingestion endpoint (US-01 / TASK-01) — mapping and delegation
 * only; see {@link ExceptionService} for the actual logic.
 */
@RestController
@RequestMapping("/api/v1/exceptions")
public class ExceptionController {

    private static final Logger log = LoggerFactory.getLogger(ExceptionController.class);

    private final ExceptionService exceptionService;

    public ExceptionController(ExceptionService exceptionService) {
        this.exceptionService = exceptionService;
    }

    @PostMapping("/create")
    public ResponseEntity<ExceptionResponse> createException(@Valid @RequestBody ExceptionEventRequest request) {
        log.info("Received exception ingestion request: source_system={}, event_type={}, invoice_id={}",
                request.getSourceSystem(), request.getEventType(),
                request.getReferences() != null ? request.getReferences().getInvoiceId() : null);

        ExceptionResponse response = exceptionService.ingest(request);

        log.info("Exception ingestion request handled: exception_id={}, status={}, httpStatus={}",
                response.getExceptionId(), response.getStatus(), HttpStatus.CREATED.value());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
