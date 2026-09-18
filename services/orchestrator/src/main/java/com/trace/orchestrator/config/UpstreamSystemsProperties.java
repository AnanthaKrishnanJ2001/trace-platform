package com.trace.orchestrator.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The allow-list of registered upstream system codes (TDD §3.3), configured
 * under {@code trace.upstream-systems.codes} in {@code application.yml}.
 *
 * <p>This is a placeholder registry — see the gap noted on
 * {@link com.trace.orchestrator.dto.validation.RegisteredUpstreamSystem}.
 */
@Component
@ConfigurationProperties(prefix = "trace.upstream-systems")
public class UpstreamSystemsProperties {

    private List<String> codes = List.of();

    public List<String> getCodes() {
        return codes;
    }

    public void setCodes(List<String> codes) {
        this.codes = codes;
    }
}
