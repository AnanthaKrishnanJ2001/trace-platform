package com.trace.orchestrator.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trace.orchestrator.config.UpstreamSystemsProperties;
import com.trace.orchestrator.dto.ExceptionResponse;
import com.trace.orchestrator.dto.validation.RegisteredUpstreamSystemValidator;
import com.trace.orchestrator.exception.IdempotencyKeyReuseException;
import com.trace.orchestrator.exception.IdempotentRequestInProgressException;
import com.trace.orchestrator.exception.InvalidIdempotencyKeyException;
import com.trace.orchestrator.exception.ReferencedRecordNotFoundException;
import com.trace.orchestrator.service.ExceptionIngestionService;
import com.trace.orchestrator.service.ExceptionIngestionService.IngestionResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * @WebMvcTest slice for {@link ExceptionController} — covers the two Gherkin
 * scenarios from US-01/TASK-01 plus one more validation edge case (invalid
 * reference ID pattern), per the task's test list.
 *
 * <p>Security filters are disabled ({@code addFilters = false}): inbound
 * authentication for this endpoint isn't part of TASK-01's scope (no
 * SecurityFilterChain exists yet in {@code services/orchestrator}), so this
 * test targets the controller's own request handling in isolation from that.
 */
@WebMvcTest(ExceptionController.class)
@AutoConfigureMockMvc(addFilters = false)
class ExceptionControllerTest {

    private static final String VALID_REQUEST_JSON = """
            {
              "source_system": "SAP_AP_MODULE",
              "event_type": "PAYMENT_EXCEPTION",
              "references": {
                "invoice_id": "INV-10492",
                "po_id": "PO-7821",
                "vendor_id": "VEND-3391"
              },
              "raw_amount": 52000.00,
              "currency": "INR",
              "event_timestamp": "2026-09-15T09:41:12Z"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExceptionIngestionService ingestionService;

    /** Provides the beans {@code @RegisteredUpstreamSystem}'s validator needs, without relying on YAML binding in the slice. */
    @TestConfiguration
    static class ValidationSupportConfig {

        @Bean
        UpstreamSystemsProperties upstreamSystemsProperties() {
            UpstreamSystemsProperties properties = new UpstreamSystemsProperties();
            properties.setCodes(List.of("SAP_AP_MODULE"));
            return properties;
        }

        @Bean
        RegisteredUpstreamSystemValidator registeredUpstreamSystemValidator(
                UpstreamSystemsProperties upstreamSystemsProperties) {
            return new RegisteredUpstreamSystemValidator(upstreamSystemsProperties);
        }
    }

    @Test
    void createException_withValidPayload_returns201WithDetectedStatusAndClassification() throws Exception {
        ExceptionResponse.Links links = new ExceptionResponse.Links(
                "/api/v1/exceptions/EXC-88231", "/api/v1/exceptions/EXC-88231/investigation");
        ExceptionResponse canned = new ExceptionResponse(
                "EXC-88231", "DETECTED", "PO_INVOICE_MISMATCH", "HIGH",
                Instant.parse("2026-09-15T09:41:13Z"), links);
        when(ingestionService.ingest(any(), any())).thenReturn(new IngestionResult(canned, false));

        mockMvc.perform(post("/api/v1/exceptions/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exception_id").value("EXC-88231"))
                .andExpect(jsonPath("$.status").value("DETECTED"))
                .andExpect(jsonPath("$.type").value("PO_INVOICE_MISMATCH"))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.links.self").value("/api/v1/exceptions/EXC-88231"))
                .andExpect(jsonPath("$.links.investigation").value("/api/v1/exceptions/EXC-88231/investigation"));
    }

    @Test
    void createException_withUnknownReferencedInvoice_returns422WithErrorEnvelope() throws Exception {
        when(ingestionService.ingest(any(), any()))
                .thenThrow(new ReferencedRecordNotFoundException("Referenced invoice does not exist: INV-10492"));

        mockMvc.perform(post("/api/v1/exceptions/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_REFERENCE"))
                .andExpect(jsonPath("$.path").value("/api/v1/exceptions/create"))
                .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    void createException_withMissingSourceSystem_returns400WithErrorEnvelope() throws Exception {
        String requestJson = """
                {
                  "event_type": "PAYMENT_EXCEPTION",
                  "references": {
                    "invoice_id": "INV-10492",
                    "po_id": "PO-7821",
                    "vendor_id": "VEND-3391"
                  },
                  "raw_amount": 52000.00,
                  "currency": "INR",
                  "event_timestamp": "2026-09-15T09:41:12Z"
                }
                """;

        mockMvc.perform(post("/api/v1/exceptions/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/api/v1/exceptions/create"))
                .andExpect(jsonPath("$.trace_id").exists())
                .andExpect(jsonPath("$.details[0].field").value("source_system"));
    }

    @Test
    void createException_withInvalidReferenceIdPattern_returns400WithErrorEnvelope() throws Exception {
        String requestJson = objectMapper.readTree(VALID_REQUEST_JSON).toString()
                .replace("PO-7821", "not-a-valid-po-id");

        mockMvc.perform(post("/api/v1/exceptions/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("references.po_id"));
    }

    private static ExceptionResponse cannedResponse() {
        return new ExceptionResponse(
                "EXC-88231", "DETECTED", "PO_INVOICE_MISMATCH", "HIGH",
                Instant.parse("2026-09-15T09:41:13Z"),
                new ExceptionResponse.Links("/api/v1/exceptions/EXC-88231",
                        "/api/v1/exceptions/EXC-88231/investigation"));
    }

    @Test
    void createException_withIdempotencyKey_passesKeyToServiceAndIsNotMarkedReplayed() throws Exception {
        when(ingestionService.ingest(any(), any())).thenReturn(new IngestionResult(cannedResponse(), false));

        mockMvc.perform(post("/api/v1/exceptions/create")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        verify(ingestionService).ingest(eq("key-123"), any());
    }

    @Test
    void createException_withoutIdempotencyKey_passesNullKeyToService() throws Exception {
        when(ingestionService.ingest(any(), any())).thenReturn(new IngestionResult(cannedResponse(), false));

        mockMvc.perform(post("/api/v1/exceptions/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isCreated());

        verify(ingestionService).ingest(isNull(), any());
    }

    @Test
    void createException_whenReplayed_returns201WithOriginalBodyAndReplayHeader() throws Exception {
        when(ingestionService.ingest(any(), any())).thenReturn(new IngestionResult(cannedResponse(), true));

        mockMvc.perform(post("/api/v1/exceptions/create")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.exception_id").value("EXC-88231"));
    }

    @Test
    void createException_whenKeyStillInProgress_returns409WithErrorEnvelope() throws Exception {
        when(ingestionService.ingest(any(), any()))
                .thenThrow(new IdempotentRequestInProgressException("still being processed"));

        mockMvc.perform(post("/api/v1/exceptions/create")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_IN_PROGRESS"))
                .andExpect(jsonPath("$.path").value("/api/v1/exceptions/create"))
                .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    void createException_whenKeyReusedWithDifferentBody_returns422WithErrorEnvelope() throws Exception {
        when(ingestionService.ingest(any(), any()))
                .thenThrow(new IdempotencyKeyReuseException("different body"));

        mockMvc.perform(post("/api/v1/exceptions/create")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    void createException_withMalformedIdempotencyKey_returns400WithErrorEnvelope() throws Exception {
        when(ingestionService.ingest(any(), any()))
                .thenThrow(new InvalidIdempotencyKeyException("bad key"));

        mockMvc.perform(post("/api/v1/exceptions/create")
                        .header("Idempotency-Key", "not valid!")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
    }
}
