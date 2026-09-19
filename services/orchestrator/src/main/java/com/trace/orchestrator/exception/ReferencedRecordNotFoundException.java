package com.trace.orchestrator.exception;

/**
 * Thrown when an exception event references an {@code invoice_id}/{@code po_id}
 * that passed DTO pattern validation but doesn't exist in TRACE's own tables
 * yet — mapped by {@link GlobalExceptionHandler} to the same 422
 * {@code UNPROCESSABLE_REFERENCE} envelope as a raw FK violation would
 * produce, just raised explicitly (before the classifier needs the row)
 * rather than left to surface as a {@code DataIntegrityViolationException}
 * on insert.
 */
public class ReferencedRecordNotFoundException extends RuntimeException {

    public ReferencedRecordNotFoundException(String message) {
        super(message);
    }
}
