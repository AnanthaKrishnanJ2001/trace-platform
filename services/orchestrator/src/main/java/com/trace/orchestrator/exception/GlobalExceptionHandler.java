package com.trace.orchestrator.exception;

import com.trace.orchestrator.dto.ErrorEnvelope;
import com.trace.orchestrator.security.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps every failure to the standard error envelope (root {@code CLAUDE.md};
 * TDD §3.4) — nothing under {@code services/orchestrator} should let a raw
 * stack trace or Spring's default error body reach a client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<ErrorEnvelope.Detail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toDetail)
                .toList();

        log.debug("Validation failed on {}: {} field error(s) — {}", request.getRequestURI(), details.size(),
                details);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more fields failed validation.",
                request, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorEnvelope> handleMalformedRequest(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        log.debug("Malformed request body on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request body is missing or malformed JSON.",
                request, null);
    }

    /**
     * A referenced invoice/PO/vendor doesn't exist yet in TRACE's own tables
     * — well-formed and individually valid, but semantically unprocessable
     * (TDD §3.3's 422 band), since {@code exceptions.invoice_id/po_id/vendor_id}
     * are foreign keys. Not covered by TASK-01's Gherkin criteria, but the
     * "never surface a raw stack trace" rule still applies.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleDataIntegrityViolation(DataIntegrityViolationException ex,
            HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE_REFERENCE",
                "One or more referenced records (invoice/purchase order/vendor) do not exist.", request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.",
                request, null);
    }

    private static ErrorEnvelope.Detail toDetail(FieldError fieldError) {
        return new ErrorEnvelope.Detail(toSnakeCasePath(fieldError.getField()), fieldError.getDefaultMessage());
    }

    /** "references.invoiceId" -> "references.invoice_id", matching the JSON field names in the contract. */
    private static String toSnakeCasePath(String javaFieldPath) {
        String[] segments = javaFieldPath.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            segments[i] = segments[i].replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
        }
        return String.join(".", segments);
    }

    private ResponseEntity<ErrorEnvelope> build(HttpStatus status, String error, String message,
            HttpServletRequest request, List<ErrorEnvelope.Detail> details) {
        ErrorEnvelope envelope = new ErrorEnvelope(
                Instant.now(),
                status.value(),
                error,
                message,
                request.getRequestURI(),
                resolveTraceId(request),
                details);
        return ResponseEntity.status(status).body(envelope);
    }

    /**
     * Reads the trace_id {@link TraceIdFilter} attaches to the request. Falls
     * back to generating one if the filter didn't run (e.g. a slice test with
     * filters disabled) so the envelope's required {@code trace_id} is never
     * missing.
     */
    private static String resolveTraceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return attribute != null ? attribute.toString() : UUID.randomUUID().toString();
    }
}
