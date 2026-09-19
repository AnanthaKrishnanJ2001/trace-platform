package com.trace.orchestrator.service;

import com.trace.orchestrator.constant.ServiceConstants;
import com.trace.orchestrator.dto.ExceptionEventRequest;
import com.trace.orchestrator.dto.ExceptionResponse;
import com.trace.orchestrator.exception.IdempotencyKeyReuseException;
import com.trace.orchestrator.exception.IdempotentRequestInProgressException;
import com.trace.orchestrator.exception.InvalidIdempotencyKeyException;
import com.trace.orchestrator.service.IdempotencyStore.ClaimResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Idempotency guard in front of {@link ExceptionService#ingest} (TDD §4.2:
 * "Idempotency guard for POST /api/v1/exceptions — prevent duplicate
 * ingestion on client retry").
 *
 * <p>Deliberately <em>not</em> transactional and a separate bean from
 * {@link ExceptionService}: the key must be marked complete only after the
 * database transaction has committed, otherwise a rollback would leave Redis
 * replaying a response for a row that doesn't exist.
 *
 * <p>Behaviour by case:
 * <ul>
 *   <li>No {@code Idempotency-Key} — ingest as before; every call creates a new exception.</li>
 *   <li>New key — claim it, ingest, store the response.</li>
 *   <li>Key already completed, same body — replay the stored response; nothing is created.</li>
 *   <li>Key already completed, different body — {@link IdempotencyKeyReuseException}.</li>
 *   <li>Key still being processed — {@link IdempotentRequestInProgressException}.</li>
 *   <li>Ingestion fails — the key is released, so a corrected retry can reuse it.
 *       Failures are never cached.</li>
 * </ul>
 *
 * <p>If Redis itself is unreachable this <em>fails open</em>: ingestion
 * proceeds unguarded and a warning is logged. Losing duplicate protection
 * during a Redis outage is preferred to refusing every exception event.
 */
@Service
public class ExceptionIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ExceptionIngestionService.class);

    private final ExceptionService exceptionService;
    private final IdempotencyStore idempotencyStore;

    public ExceptionIngestionService(ExceptionService exceptionService, IdempotencyStore idempotencyStore) {
        this.exceptionService = exceptionService;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * @param idempotencyKey the raw {@code Idempotency-Key} header, or {@code null} if the client sent none
     */
    public IngestionResult ingest(String idempotencyKey, ExceptionEventRequest request) {
        if (idempotencyKey == null) {
            return new IngestionResult(exceptionService.ingest(request), false);
        }
        if (!ServiceConstants.IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must be 1-128 characters from A-Z a-z 0-9 . _ : -");
        }

        String fingerprint = fingerprint(request);

        ClaimResult claim;
        try {
            claim = idempotencyStore.claim(idempotencyKey, fingerprint);
        } catch (DataAccessException ex) {
            log.warn("Idempotency store unavailable — ingesting without duplicate protection: {}", ex.getMessage());
            return new IngestionResult(exceptionService.ingest(request), false);
        }

        switch (claim.status()) {
            case COMPLETED -> {
                if (!fingerprint.equals(claim.fingerprint())) {
                    throw new IdempotencyKeyReuseException(
                            "Idempotency-Key was already used with a different request body.");
                }
                log.info("Replaying stored response for repeated Idempotency-Key: exception_id={}",
                        claim.response().getExceptionId());
                return new IngestionResult(claim.response(), true);
            }
            case IN_PROGRESS -> throw new IdempotentRequestInProgressException(
                    "A request with this Idempotency-Key is still being processed. Retry shortly.");
            case CLAIMED -> {
                // fall through to ingestion below
            }
        }

        ExceptionResponse response;
        try {
            response = exceptionService.ingest(request);
        } catch (RuntimeException ex) {
            release(idempotencyKey);
            throw ex;
        }

        complete(idempotencyKey, fingerprint, response);
        return new IngestionResult(response, false);
    }

    private void complete(String key, String fingerprint, ExceptionResponse response) {
        try {
            idempotencyStore.complete(key, fingerprint, response);
        } catch (DataAccessException ex) {
            // The exception row is already committed; retries will get 409 until the in-progress TTL lapses.
            log.error("Exception {} was created but its Idempotency-Key could not be recorded: {}",
                    response.getExceptionId(), ex.getMessage());
        }
    }

    private void release(String key) {
        try {
            idempotencyStore.release(key);
        } catch (DataAccessException ex) {
            log.warn("Could not release Idempotency-Key after failed ingestion (it will expire on its own): {}",
                    ex.getMessage());
        }
    }

    /** SHA-256 over every field of the request, so "same key, different body" is detectable. */
    static String fingerprint(ExceptionEventRequest request) {
        ExceptionEventRequest.References refs = request.getReferences();
        String canonical = String.join("",
                request.getSourceSystem(),
                request.getEventType(),
                refs.getInvoiceId(),
                refs.getPoId(),
                refs.getVendorId(),
                request.getRawAmount().stripTrailingZeros().toPlainString(),
                request.getCurrency(),
                request.getEventTimestamp().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is guaranteed by every JVM", ex);
        }
    }

    /**
     * @param response the response to send
     * @param replayed true if {@code response} is a replay of an earlier request rather than a new creation
     */
    public record IngestionResult(ExceptionResponse response, boolean replayed) {
    }
}
