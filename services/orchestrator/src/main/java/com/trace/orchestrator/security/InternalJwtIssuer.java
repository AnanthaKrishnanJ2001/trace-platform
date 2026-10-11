package com.trace.orchestrator.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.trace.orchestrator.config.InternalJwtProperties;
import com.trace.orchestrator.constant.ServiceConstants;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Issues the short-lived JWT that authenticates every Spring Boot Core →
 * FastAPI call (root {@code CLAUDE.md}; TDD §1.2): HS256, audience-restricted
 * to the AI service. A fresh token is minted per call (cheap, and a retry
 * never presents an expired one).
 *
 * <p>Neither the token nor the signing secret is ever logged, and a secret
 * too short for HS256 fails startup with a message that does not echo it.
 */
@Component
public class InternalJwtIssuer {

    private final JwtEncoder encoder;
    private final String audience;
    private final Duration ttl;

    public InternalJwtIssuer(InternalJwtProperties properties) {
        byte[] secret = properties.getSecret() == null ? new byte[0]
                : properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < ServiceConstants.MIN_INTERNAL_JWT_SECRET_BYTES) {
            throw new IllegalStateException("trace.security.internal-jwt.secret (INTERNAL_JWT_SECRET) must be at least "
                    + ServiceConstants.MIN_INTERNAL_JWT_SECRET_BYTES + " bytes for HS256");
        }
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(secret, "HmacSHA256")));
        this.audience = properties.getAudience();
        this.ttl = Duration.ofSeconds(properties.getTtlSeconds());
    }

    /** Returns a signed compact JWT valid for the configured TTL. */
    public String issue() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ServiceConstants.INTERNAL_JWT_ISSUER)
                .subject(ServiceConstants.INTERNAL_JWT_ISSUER)
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
