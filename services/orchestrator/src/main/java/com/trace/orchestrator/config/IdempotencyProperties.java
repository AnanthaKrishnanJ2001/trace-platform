package com.trace.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the {@code Idempotency-Key} guard on exception ingestion
 * (TDD §4.2: {@code idem:{idempotency_key}}, ~10 min TTL), configured under
 * {@code trace.idempotency} in {@code application.yml}.
 *
 * <p>{@code ttl} is how long a <em>completed</em> key keeps replaying its
 * original response. {@code inProgressTtl} is deliberately shorter: it bounds
 * how long retries are refused with 409 if the process dies between claiming
 * a key and finishing the request.
 */
@Component
@ConfigurationProperties(prefix = "trace.idempotency")
public class IdempotencyProperties {

    private String keyPrefix = "idem:";
    private Duration ttl = Duration.ofMinutes(10);
    private Duration inProgressTtl = Duration.ofSeconds(30);

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getInProgressTtl() {
        return inProgressTtl;
    }

    public void setInProgressTtl(Duration inProgressTtl) {
        this.inProgressTtl = inProgressTtl;
    }
}
