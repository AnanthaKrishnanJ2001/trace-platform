package com.trace.orchestrator.exception;

/**
 * Thrown when a request arrives with an {@code Idempotency-Key} whose first
 * request hasn't finished yet — mapped by {@link GlobalExceptionHandler} to
 * 409 {@code REQUEST_IN_PROGRESS}; the client should retry shortly.
 */
public class IdempotentRequestInProgressException extends RuntimeException {

    public IdempotentRequestInProgressException(String message) {
        super(message);
    }
}
