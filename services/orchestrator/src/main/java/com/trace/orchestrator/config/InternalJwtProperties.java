package com.trace.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the internal service-to-service JWT (TDD §1.2), configured
 * under {@code trace.security.internal-jwt}. The secret is shared with the
 * FastAPI service, which verifies the token; it must never be logged.
 */
@Component
@ConfigurationProperties(prefix = "trace.security.internal-jwt")
public class InternalJwtProperties {

    private String secret;
    private String audience = "fastapi-ai-service";
    private long ttlSeconds = 300;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
