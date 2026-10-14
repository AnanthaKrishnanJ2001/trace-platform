package com.trace.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trace.orchestrator.client.InvestigationClient;
import com.trace.orchestrator.config.InvestigationProperties;
import com.trace.orchestrator.domain.ExceptionEntity;
import com.trace.orchestrator.dto.InvestigationRequest;
import com.trace.orchestrator.dto.InvestigationResult;
import com.trace.orchestrator.exception.InvestigationUnavailableException;
import com.trace.orchestrator.repository.ExceptionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The claim-then-call-then-record flow of {@link InvestigationService}: a lost
 * claim never calls the Engine, and once claimed the row always leaves
 * {@code INVESTIGATING}.
 */
@ExtendWith(MockitoExtension.class)
class InvestigationServiceTest {

    private static final String ID = "EXC-00011";
    private static final String TRACE_ID = "trace-abc-123";

    @Mock
    private ExceptionRepository exceptionRepository;

    @Mock
    private InvestigationClient investigationClient;

    private InvestigationService service;

    @BeforeEach
    void setUp() {
        service = new InvestigationService(exceptionRepository, investigationClient, new InvestigationProperties());
    }

    @Test
    void investigate_whenClaimLost_neverCallsEngineOrTouchesRow() {
        when(exceptionRepository.transitionStatus(eq(ID), eq("DETECTED"), eq("INVESTIGATING"), any(Instant.class)))
                .thenReturn(0);

        service.investigate(ID, TRACE_ID);

        verifyNoInteractions(investigationClient);
        verify(exceptionRepository, never()).findById(anyString());
        verify(exceptionRepository, never()).recordInvestigationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void investigate_whenClaimWon_sendsRequestBuiltFromRowAndForwardsTraceId() {
        claimWon();
        when(investigationClient.investigate(any(), eq(TRACE_ID))).thenReturn(result("COMPLETED", "PRICE_VARIANCE", 92));

        service.investigate(ID, TRACE_ID);

        ArgumentCaptor<InvestigationRequest> captor = ArgumentCaptor.forClass(InvestigationRequest.class);
        verify(investigationClient).investigate(captor.capture(), eq(TRACE_ID));
        InvestigationRequest sent = captor.getValue();
        assertThat(sent.exceptionId()).isEqualTo(ID);
        assertThat(sent.exceptionType()).isEqualTo("PO_INVOICE_MISMATCH");
        assertThat(sent.investigationId()).startsWith("INVST-");
        assertThat(sent.references()).isEqualTo(new InvestigationRequest.References("INV-10493", "PO-7822", "VEND-3391"));
        assertThat(sent.context().confidenceThreshold()).isEqualTo(70);
    }

    @Test
    void investigate_confidentResult_recordsInvestigatedWithRootCause() {
        claimWon();
        when(investigationClient.investigate(any(), anyString())).thenReturn(result("COMPLETED", "PRICE_VARIANCE", 92));

        service.investigate(ID, TRACE_ID);

        verify(exceptionRepository).recordInvestigationOutcome(eq(ID), eq("INVESTIGATING"), eq("INVESTIGATED"),
                eq("PRICE_VARIANCE"), eq((short) 92), any(Instant.class));
    }

    @Test
    void investigate_confidenceAtThreshold_isTrusted() {
        claimWon();
        when(investigationClient.investigate(any(), anyString())).thenReturn(result("COMPLETED", "PRICE_VARIANCE", 70));

        service.investigate(ID, TRACE_ID);

        verify(exceptionRepository).recordInvestigationOutcome(eq(ID), eq("INVESTIGATING"), eq("INVESTIGATED"),
                eq("PRICE_VARIANCE"), eq((short) 70), any(Instant.class));
    }

    @Test
    void investigate_lowConfidence_needsHumanReview_butKeepsRootCause() {
        claimWon();
        when(investigationClient.investigate(any(), anyString())).thenReturn(result("COMPLETED", "PRICE_VARIANCE", 69));

        service.investigate(ID, TRACE_ID);

        verify(exceptionRepository).recordInvestigationOutcome(eq(ID), eq("INVESTIGATING"),
                eq("NEEDS_HUMAN_REVIEW"), eq("PRICE_VARIANCE"), eq((short) 69), any(Instant.class));
    }

    @Test
    void investigate_partialResultWithoutRootCause_needsHumanReview() {
        claimWon();
        when(investigationClient.investigate(any(), anyString())).thenReturn(
                new InvestigationResult("INVST-1", "PARTIAL", null, null, null, null));

        service.investigate(ID, TRACE_ID);

        verify(exceptionRepository).recordInvestigationOutcome(eq(ID), eq("INVESTIGATING"),
                eq("NEEDS_HUMAN_REVIEW"), isNull(), isNull(), any(Instant.class));
    }

    @Test
    void investigate_outOfRangeConfidence_isDroppedAndNeedsHumanReview() {
        claimWon();
        when(investigationClient.investigate(any(), anyString())).thenReturn(result("COMPLETED", "PRICE_VARIANCE", 250));

        service.investigate(ID, TRACE_ID);

        // 250 would violate the DB CHECK (0-100), so it must never be written.
        verify(exceptionRepository).recordInvestigationOutcome(eq(ID), eq("INVESTIGATING"),
                eq("NEEDS_HUMAN_REVIEW"), eq("PRICE_VARIANCE"), isNull(), any(Instant.class));
    }

    @Test
    void investigate_overlongLabel_isTruncatedToColumnWidth() {
        claimWon();
        when(investigationClient.investigate(any(), anyString())).thenReturn(result("COMPLETED", "X".repeat(200), 90));

        service.investigate(ID, TRACE_ID);

        verify(exceptionRepository).recordInvestigationOutcome(eq(ID), eq("INVESTIGATING"), eq("INVESTIGATED"),
                eq("X".repeat(80)), eq((short) 90), any(Instant.class));
    }

    @Test
    void investigate_failedResult_marksUnavailable() {
        claimWon();
        when(investigationClient.investigate(any(), anyString())).thenReturn(
                new InvestigationResult("INVST-1", "FAILED", null, null, null, null));

        service.investigate(ID, TRACE_ID);

        verifyMarkedUnavailable();
        verify(exceptionRepository, never()).recordInvestigationOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void investigate_engineUnavailable_marksUnavailable() {
        claimWon();
        when(investigationClient.investigate(any(), anyString()))
                .thenThrow(new InvestigationUnavailableException("breaker open", null));

        service.investigate(ID, TRACE_ID);

        verifyMarkedUnavailable();
    }

    @Test
    void investigate_unexpectedFailure_stillLeavesInvestigating() {
        claimWon();
        when(investigationClient.investigate(any(), anyString())).thenThrow(new IllegalStateException("boom"));

        service.investigate(ID, TRACE_ID);

        verifyMarkedUnavailable();
    }

    @Test
    void investigate_withoutTraceId_generatesOneSoEngineLogsCanStillBeJoined() {
        claimWon();
        when(investigationClient.investigate(any(), anyString())).thenReturn(result("COMPLETED", "PRICE_VARIANCE", 92));

        service.investigate(ID, null);

        ArgumentCaptor<String> traceCaptor = ArgumentCaptor.forClass(String.class);
        verify(investigationClient).investigate(any(), traceCaptor.capture());
        assertThat(traceCaptor.getValue()).isNotBlank();
    }

    private void claimWon() {
        when(exceptionRepository.transitionStatus(eq(ID), eq("DETECTED"), eq("INVESTIGATING"), any(Instant.class)))
                .thenReturn(1);
        when(exceptionRepository.findById(ID)).thenReturn(Optional.of(new ExceptionEntity(ID, "SAP_AP_MODULE",
                "PO_INVOICE_MISMATCH", "HIGH", "INVESTIGATING", "INV-10493", "PO-7822", "VEND-3391", Instant.now(),
                Instant.now())));
    }

    private void verifyMarkedUnavailable() {
        verify(exceptionRepository).transitionStatus(eq(ID), eq("INVESTIGATING"), eq("INVESTIGATION_UNAVAILABLE"),
                any(Instant.class));
    }

    private static InvestigationResult result(String status, String label, int confidence) {
        return new InvestigationResult("INVST-1", status, null,
                new InvestigationResult.RootCause(label, confidence, List.of("INV-10493")), null, null);
    }
}
