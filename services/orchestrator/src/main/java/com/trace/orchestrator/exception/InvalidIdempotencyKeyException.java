package com.trace.orchestrator.exception;

/**
 * Thrown when the {@code Idempotency-Key} header is present but malformed
 * (blank, too long, or containing characters outside the contract's
 * pattern) — mapped by {@link GlobalExceptionHandler} to a 400
 * {@code VALIDATION_FAILED} envelope.
 */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
