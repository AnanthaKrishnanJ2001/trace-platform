package com.trace.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trace.orchestrator.config.IdempotencyProperties;
import com.trace.orchestrator.constant.ServiceConstants;
import com.trace.orchestrator.dto.ExceptionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed store for {@code Idempotency-Key} state (TDD §4.2,
 * {@code idem:{idempotency_key}}). A key is claimed atomically with
 * {@code SET NX}, so two concurrent requests carrying the same key can never
 * both proceed; the winner later {@link #complete}s it with the response to
 * replay, or {@link #release}s it if the request failed.
 *
 * <p>Redis errors are deliberately <em>not</em> handled here — they surface
 * as {@link org.springframework.dao.DataAccessException} so the caller
 * decides whether to fail open or closed.
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;

    public IdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper, IdempotencyProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Tries to claim {@code key} for a request whose body hashes to
     * {@code fingerprint}. Returns {@link ClaimResult#claimed()} if this
     * caller now owns the key, otherwise describes what already holds it.
     */
    public ClaimResult claim(String key, String fingerprint) {
        String redisKey = properties.getKeyPrefix() + key;
        String inProgressValue = write(new Entry(State.IN_PROGRESS, fingerprint, null));

        for (int attempt = 0; attempt < ServiceConstants.MAX_CLAIM_ATTEMPTS; attempt++) {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, inProgressValue,
                    properties.getInProgressTtl()))) {
                return ClaimResult.claimed();
            }
            String existing = redis.opsForValue().get(redisKey);
            if (existing != null) {
                return toClaimResult(redisKey, existing);
            }
            // Expired between SET NX and GET — go round again and claim it.
        }
        return ClaimResult.inProgress();
    }

    /** Records the finished response so later requests with this key replay it. */
    public void complete(String key, String fingerprint, ExceptionResponse response) {
        redis.opsForValue().set(properties.getKeyPrefix() + key,
                write(new Entry(State.COMPLETED, fingerprint, response)), properties.getTtl());
    }

    /** Frees a claimed key after a failed request so the client can retry with the same key. */
    public void release(String key) {
        redis.delete(properties.getKeyPrefix() + key);
    }

    private ClaimResult toClaimResult(String redisKey, String existing) {
        try {
            Entry entry = objectMapper.readValue(existing, Entry.class);
            if (entry.state() == State.COMPLETED && entry.response() != null) {
                return ClaimResult.completed(entry.fingerprint(), entry.response());
            }
            return ClaimResult.inProgress();
        } catch (JsonProcessingException ex) {
            // Unreadable entry: refusing (409 until it expires) is safe; guessing could create a duplicate.
            log.warn("Unreadable idempotency entry at {}: {}", redisKey, ex.getOriginalMessage());
            return ClaimResult.inProgress();
        }
    }

    private String write(Entry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize idempotency entry", ex);
        }
    }

    enum State {
        IN_PROGRESS,
        COMPLETED
    }

    /** What is stored in Redis under the key. */
    record Entry(State state, String fingerprint, ExceptionResponse response) {
    }

    /** Outcome of {@link #claim}. */
    public record ClaimResult(Status status, String fingerprint, ExceptionResponse response) {

        public enum Status {
            /** This caller now owns the key and should process the request. */
            CLAIMED,
            /** Another request with this key is still being processed. */
            IN_PROGRESS,
            /** An earlier request with this key finished; {@code response} is what it returned. */
            COMPLETED
        }

        static ClaimResult claimed() {
            return new ClaimResult(Status.CLAIMED, null, null);
        }

        static ClaimResult inProgress() {
            return new ClaimResult(Status.IN_PROGRESS, null, null);
        }

        static ClaimResult completed(String fingerprint, ExceptionResponse response) {
            return new ClaimResult(Status.COMPLETED, fingerprint, response);
        }
    }
}
