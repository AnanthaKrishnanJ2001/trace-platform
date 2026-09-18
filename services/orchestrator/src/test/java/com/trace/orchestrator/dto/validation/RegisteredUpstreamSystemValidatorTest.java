package com.trace.orchestrator.dto.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.trace.orchestrator.config.UpstreamSystemsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegisteredUpstreamSystemValidatorTest {

    @Test
    void isValid_acceptsCodeInAllowList() {
        UpstreamSystemsProperties properties = new UpstreamSystemsProperties();
        properties.setCodes(List.of("SAP_AP_MODULE"));
        RegisteredUpstreamSystemValidator validator = new RegisteredUpstreamSystemValidator(properties);

        assertThat(validator.isValid("SAP_AP_MODULE", null)).isTrue();
    }

    @Test
    void isValid_rejectsCodeNotInAllowList() {
        UpstreamSystemsProperties properties = new UpstreamSystemsProperties();
        properties.setCodes(List.of("SAP_AP_MODULE"));
        RegisteredUpstreamSystemValidator validator = new RegisteredUpstreamSystemValidator(properties);

        assertThat(validator.isValid("UNKNOWN_SYSTEM", null)).isFalse();
    }
}
