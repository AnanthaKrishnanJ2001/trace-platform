package com.trace.orchestrator.exception;

/**
 * Thrown when an {@code Idempotency-Key} that already completed a request is
 * re-sent with a <em>different</em> body — replaying the old response would
 * silently answer a question the client didn't ask, so it's refused. Mapped
 * by {@link GlobalExceptionHandler} to 422 {@code IDEMPOTENCY_KEY_REUSED}.
 */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException(String message) {
        super(message);
    }
}
