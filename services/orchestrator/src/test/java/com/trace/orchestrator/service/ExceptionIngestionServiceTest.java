package com.trace.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trace.orchestrator.dto.ExceptionEventRequest;
import com.trace.orchestrator.dto.ExceptionResponse;
import com.trace.orchestrator.exception.IdempotencyKeyReuseException;
import com.trace.orchestrator.exception.IdempotentRequestInProgressException;
import com.trace.orchestrator.exception.InvalidIdempotencyKeyException;
import com.trace.orchestrator.exception.ReferencedRecordNotFoundException;
import com.trace.orchestrator.service.ExceptionIngestionService.IngestionResult;
import com.trace.orchestrator.service.IdempotencyStore.ClaimResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * Mockito unit tests for {@link ExceptionIngestionService}'s idempotency
 * behaviour (TDD §4.2). {@link ExceptionService} and {@link IdempotencyStore}
 * are mocked; the store's Redis behaviour has its own test.
 */
@ExtendWith(MockitoExtension.class)
class ExceptionIngestionServiceTest {

    private static final String KEY = "3f0c2c9e-7d0a-4b53-9d0e-1a2b3c4d5e6f";

    @Mock
    private ExceptionService exceptionService;

    @Mock
    private IdempotencyStore idempotencyStore;

    @InjectMocks
    private ExceptionIngestionService service;

    @Test
    void ingest_withoutKey_ingestsDirectlyAndNeverTouchesTheStore() {
        ExceptionEventRequest request = request("PAYMENT_EXCEPTION");
        ExceptionResponse created = response("EXC-00010");
        when(exceptionService.ingest(request)).thenReturn(created);

        IngestionResult result = service.ingest(null, request);

        assertThat(result.response()).isSameAs(created);
        assertThat(result.replayed()).isFalse();
        verifyNoInteractions(idempotencyStore);
    }

    @Test
    void ingest_withNewKey_claimsIngestsThenRecordsTheResponse() {
        ExceptionEventRequest request = request("PAYMENT_EXCEPTION");
        ExceptionResponse created = response("EXC-00010");
        String fingerprint = ExceptionIngestionService.fingerprint(request);
        when(idempotencyStore.claim(KEY, fingerprint)).thenReturn(ClaimResult.claimed());
        when(exceptionService.ingest(request)).thenReturn(created);

        IngestionResult result = service.ingest(KEY, request);

        assertThat(result.response()).isSameAs(created);
        assertThat(result.replayed()).isFalse();
        verify(idempotencyStore).complete(KEY, fingerprint, created);
        verify(idempotencyStore, never()).release(anyString());
    }

    @Test
    void ingest_withCompletedKeyAndSameBody_replaysStoredResponseWithoutCreatingAnything() {
        ExceptionEventRequest request = request("PAYMENT_EXCEPTION");
        ExceptionResponse original = response("EXC-00010");
        String fingerprint = ExceptionIngestionService.fingerprint(request);
        when(idempotencyStore.claim(KEY, fingerprint)).thenReturn(ClaimResult.completed(fingerprint, original));

        IngestionResult result = service.ingest(KEY, request);

        assertThat(result.response()).isSameAs(original);
        assertThat(result.replayed()).isTrue();
        verifyNoInteractions(exceptionService);
        verify(idempotencyStore, never()).complete(anyString(), anyString(), any());
    }

    @Test
    void ingest_withCompletedKeyAndDifferentBody_isRejectedAndCreatesNothing() {
        ExceptionEventRequest original = request("PAYMENT_EXCEPTION");
        ExceptionEventRequest different = request("DUPLICATE_INVOICE");
        String originalFingerprint = ExceptionIngestionService.fingerprint(original);
        when(idempotencyStore.claim(KEY, ExceptionIngestionService.fingerprint(different)))
                .thenReturn(ClaimResult.completed(originalFingerprint, response("EXC-00010")));

        assertThatThrownBy(() -> service.ingest(KEY, different)).isInstanceOf(IdempotencyKeyReuseException.class);
        verifyNoInteractions(exceptionService);
    }

    @Test
    void ingest_whileFirstRequestStillRunning_isRejectedWithConflict() {
        ExceptionEventRequest request = request("PAYMENT_EXCEPTION");
        when(idempotencyStore.claim(KEY, ExceptionIngestionService.fingerprint(request)))
                .thenReturn(ClaimResult.inProgress());

        assertThatThrownBy(() -> service.ingest(KEY, request))
                .isInstanceOf(IdempotentRequestInProgressException.class);
        verifyNoInteractions(exceptionService);
    }

    @Test
    void ingest_whenIngestionFails_releasesTheKeySoTheClientCanRetry() {
        ExceptionEventRequest request = request("PAYMENT_EXCEPTION");
        when(idempotencyStore.claim(KEY, ExceptionIngestionService.fingerprint(request)))
                .thenReturn(ClaimResult.claimed());
        ReferencedRecordNotFoundException failure = new ReferencedRecordNotFoundException("no such invoice");
        when(exceptionService.ingest(request)).thenThrow(failure);

        assertThatThrownBy(() -> service.ingest(KEY, request)).isSameAs(failure);

        verify(idempotencyStore).release(KEY);
        verify(idempotencyStore, never()).complete(anyString(), anyString(), any());
    }

    @Test
    void ingest_whenRedisIsDownOnClaim_failsOpenAndStillIngests() {
        ExceptionEventRequest request = request("PAYMENT_EXCEPTION");
        ExceptionResponse created = response("EXC-00010");
        when(idempotencyStore.claim(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        when(exceptionService.ingest(request)).thenReturn(created);

        IngestionResult result = service.ingest(KEY, request);

        assertThat(result.response()).isSameAs(created);
        assertThat(result.replayed()).isFalse();
    }

    @Test
    void ingest_whenRedisFailsAfterCommit_stillReturnsTheCreatedException() {
        ExceptionEventRequest request = request("PAYMENT_EXCEPTION");
        ExceptionResponse created = response("EXC-00010");
        String fingerprint = ExceptionIngestionService.fingerprint(request);
        when(idempotencyStore.claim(KEY, fingerprint)).thenReturn(ClaimResult.claimed());
        when(exceptionService.ingest(request)).thenReturn(created);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(idempotencyStore).complete(KEY, fingerprint, created);

        IngestionResult result = service.ingest(KEY, request);

        assertThat(result.response()).isSameAs(created);
    }

    @ParameterizedTest
    @MethodSource("malformedKeys")
    void ingest_withMalformedKey_isRejectedBeforeAnyWork(String badKey) {
        assertThatThrownBy(() -> service.ingest(badKey, request("PAYMENT_EXCEPTION")))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        verifyNoInteractions(idempotencyStore, exceptionService);
    }

    @ParameterizedTest
    @MethodSource("wellFormedKeys")
    void ingest_withWellFormedKey_isAccepted(String goodKey) {
        ExceptionEventRequest request = request("PAYMENT_EXCEPTION");
        when(idempotencyStore.claim(anyString(), anyString())).thenReturn(ClaimResult.claimed());
        when(exceptionService.ingest(request)).thenReturn(response("EXC-00010"));

        assertThat(service.ingest(goodKey, request).replayed()).isFalse();
    }

    @Test
    void fingerprint_isStableForEqualRequests_andChangesWhenAnyFieldChanges() {
        ExceptionEventRequest base = request("PAYMENT_EXCEPTION");

        assertThat(ExceptionIngestionService.fingerprint(request("PAYMENT_EXCEPTION")))
                .isEqualTo(ExceptionIngestionService.fingerprint(base));
        assertThat(ExceptionIngestionService.fingerprint(request("OTHER_TYPE")))
                .isNotEqualTo(ExceptionIngestionService.fingerprint(base));

        ExceptionEventRequest differentAmount = request("PAYMENT_EXCEPTION");
        differentAmount.setRawAmount(new BigDecimal("52000.01"));
        assertThat(ExceptionIngestionService.fingerprint(differentAmount))
                .isNotEqualTo(ExceptionIngestionService.fingerprint(base));
    }

    @Test
    void fingerprint_treatsEquivalentDecimalsAsTheSameAmount() {
        ExceptionEventRequest a = request("PAYMENT_EXCEPTION");
        ExceptionEventRequest b = request("PAYMENT_EXCEPTION");
        a.setRawAmount(new BigDecimal("52000.00"));
        b.setRawAmount(new BigDecimal("52000"));

        assertThat(ExceptionIngestionService.fingerprint(a)).isEqualTo(ExceptionIngestionService.fingerprint(b));
    }

    static Stream<String> malformedKeys() {
        return Stream.of("", "   ", "has space", "semi;colon", "non-ascii-ключ", "a".repeat(129));
    }

    static Stream<String> wellFormedKeys() {
        return Stream.of("a", "abc-123", "3f0c2c9e-7d0a-4b53-9d0e-1a2b3c4d5e6f", "ns:order.42_v2", "a".repeat(128));
    }

    private static ExceptionEventRequest request(String eventType) {
        ExceptionEventRequest.References refs = new ExceptionEventRequest.References();
        refs.setInvoiceId("INV-10492");
        refs.setPoId("PO-7821");
        refs.setVendorId("VEND-3391");

        ExceptionEventRequest request = new ExceptionEventRequest();
        request.setSourceSystem("SAP_AP_MODULE");
        request.setEventType(eventType);
        request.setReferences(refs);
        request.setRawAmount(new BigDecimal("52000.00"));
        request.setCurrency("INR");
        request.setEventTimestamp(Instant.parse("2026-09-15T09:41:12Z"));
        return request;
    }

    private static ExceptionResponse response(String exceptionId) {
        return new ExceptionResponse(exceptionId, "DETECTED", "PO_INVOICE_MISMATCH", "LOW",
                Instant.parse("2026-09-15T09:41:13Z"),
                new ExceptionResponse.Links("/api/v1/exceptions/" + exceptionId,
                        "/api/v1/exceptions/" + exceptionId + "/investigation"));
    }
}
