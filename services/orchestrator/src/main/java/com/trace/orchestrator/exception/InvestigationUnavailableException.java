package com.trace.orchestrator.exception;

/**
 * The AI service could not produce an investigation: circuit breaker open,
 * timeout, connection failure, or an error response (TDD §5.3). Never
 * reaches an HTTP client — investigations run in the background — so it has
 * no handler in {@link GlobalExceptionHandler}; the caller records
 * {@code INVESTIGATION_UNAVAILABLE} on the exception instead.
 */
public class InvestigationUnavailableException extends RuntimeException {

    public InvestigationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
