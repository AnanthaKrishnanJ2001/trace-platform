package com.trace.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trace.orchestrator.config.IdempotencyProperties;
import com.trace.orchestrator.dto.ExceptionResponse;
import com.trace.orchestrator.service.IdempotencyStore.ClaimResult;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Mockito unit tests for {@link IdempotencyStore} against a mocked
 * {@link StringRedisTemplate}. The ObjectMapper is built the way Spring Boot
 * builds its own (ISO-8601 dates, java.time support), so the JSON written
 * here is what production writes.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyStoreTest {

    private static final String KEY = "abc-123";
    private static final String REDIS_KEY = "idem:abc-123";
    private static final String FINGERPRINT = "fp-1";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final IdempotencyProperties properties = new IdempotencyProperties();

    private IdempotencyStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        store = new IdempotencyStore(redis, objectMapper, properties);
    }

    @Test
    void claim_whenKeyIsFree_claimsItWithTheShortInProgressTtl() {
        when(valueOps.setIfAbsent(eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(true);

        ClaimResult result = store.claim(KEY, FINGERPRINT);

        assertThat(result.status()).isEqualTo(ClaimResult.Status.CLAIMED);
    }

    @Test
    void claim_whenAnotherRequestHoldsTheKey_reportsInProgress() throws Exception {
        when(valueOps.setIfAbsent(eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(false);
        when(valueOps.get(REDIS_KEY)).thenReturn(
                objectMapper.writeValueAsString(new IdempotencyStore.Entry(
                        IdempotencyStore.State.IN_PROGRESS, FINGERPRINT, null)));

        assertThat(store.claim(KEY, FINGERPRINT).status()).isEqualTo(ClaimResult.Status.IN_PROGRESS);
    }

    @Test
    void complete_thenClaim_roundTripsTheOriginalResponse() {
        ExceptionResponse original = new ExceptionResponse("EXC-00010", "DETECTED", "PO_INVOICE_MISMATCH", "LOW",
                Instant.parse("2026-09-15T09:41:13Z"),
                new ExceptionResponse.Links("/api/v1/exceptions/EXC-00010",
                        "/api/v1/exceptions/EXC-00010/investigation"));

        store.complete(KEY, FINGERPRINT, original);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(REDIS_KEY), stored.capture(), eq(Duration.ofMinutes(10)));

        when(valueOps.setIfAbsent(eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(false);
        when(valueOps.get(REDIS_KEY)).thenReturn(stored.getValue());

        ClaimResult result = store.claim(KEY, FINGERPRINT);

        assertThat(result.status()).isEqualTo(ClaimResult.Status.COMPLETED);
        assertThat(result.fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(result.response()).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void claim_whenTheHolderExpiresBetweenSetAndGet_claimsOnTheSecondAttempt() {
        when(valueOps.setIfAbsent(eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30))))
                .thenReturn(false, true);
        when(valueOps.get(REDIS_KEY)).thenReturn(null);

        assertThat(store.claim(KEY, FINGERPRINT).status()).isEqualTo(ClaimResult.Status.CLAIMED);
    }

    @Test
    void claim_whenStoredValueIsUnreadable_refusesRatherThanRisksADuplicate() {
        when(valueOps.setIfAbsent(eq(REDIS_KEY), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(false);
        when(valueOps.get(REDIS_KEY)).thenReturn("{not json");

        assertThat(store.claim(KEY, FINGERPRINT).status()).isEqualTo(ClaimResult.Status.IN_PROGRESS);
    }

    @Test
    void release_deletesTheKey() {
        store.release(KEY);

        verify(redis).delete(REDIS_KEY);
    }
}
