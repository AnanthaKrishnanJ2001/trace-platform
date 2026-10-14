package com.trace.orchestrator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Where the FastAPI AI Service lives, configured under
 * {@code trace.ai-service}. The <em>read</em> timeout is deliberately not
 * here: it comes from the {@code investigationService} time limiter so the
 * 8-second budget is defined in exactly one place.
 *
 * <p>No defaults live here — both values come from {@code application.yml};
 * validation fails startup if either is missing.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "trace.ai-service")
public class AiServiceProperties {

    @NotBlank
    private String baseUrl;

    @NotNull
    private Duration connectTimeout;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
}
