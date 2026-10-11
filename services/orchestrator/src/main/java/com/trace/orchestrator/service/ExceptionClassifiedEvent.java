package com.trace.orchestrator.service;

/**
 * Published by {@link ExceptionService#ingest} once an exception has been
 * classified and saved. Delivered to {@link InvestigationTrigger} only after
 * the ingestion transaction commits.
 *
 * @param traceId the originating request's {@code trace_id}, carried here
 *                because request-scoped state doesn't cross to worker
 *                threads; may be {@code null} outside a request
 */
public record ExceptionClassifiedEvent(String exceptionId, String severity, String traceId) {
}
