package com.trace.orchestrator.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trace.orchestrator.config.UpstreamSystemsProperties;
import com.trace.orchestrator.dto.ExceptionResponse;
import com.trace.orchestrator.dto.validation.RegisteredUpstreamSystemValidator;
import com.trace.orchestrator.service.ExceptionService;
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
    private ExceptionService exceptionService;

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
    void createException_withValidPayload_returns201WithDetectedStatus() throws Exception {
        ExceptionResponse.Links links = new ExceptionResponse.Links(
                "/api/v1/exceptions/EXC-88231", "/api/v1/exceptions/EXC-88231/investigation");
        ExceptionResponse canned = new ExceptionResponse(
                "EXC-88231", "DETECTED", null, null, Instant.parse("2026-09-15T09:41:13Z"), links);
        when(exceptionService.ingest(any())).thenReturn(canned);

        mockMvc.perform(post("/api/v1/exceptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exception_id").value("EXC-88231"))
                .andExpect(jsonPath("$.status").value("DETECTED"))
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.severity").doesNotExist())
                .andExpect(jsonPath("$.links.self").value("/api/v1/exceptions/EXC-88231"))
                .andExpect(jsonPath("$.links.investigation").value("/api/v1/exceptions/EXC-88231/investigation"));
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

        mockMvc.perform(post("/api/v1/exceptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/api/v1/exceptions"))
                .andExpect(jsonPath("$.trace_id").exists())
                .andExpect(jsonPath("$.details[0].field").value("source_system"));
    }

    @Test
    void createException_withInvalidReferenceIdPattern_returns400WithErrorEnvelope() throws Exception {
        String requestJson = objectMapper.readTree(VALID_REQUEST_JSON).toString()
                .replace("PO-7821", "not-a-valid-po-id");

        mockMvc.perform(post("/api/v1/exceptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("references.po_id"));
    }
}
