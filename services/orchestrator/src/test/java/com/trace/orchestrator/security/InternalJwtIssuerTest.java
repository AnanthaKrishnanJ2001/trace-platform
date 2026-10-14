package com.trace.orchestrator.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.trace.orchestrator.config.InternalJwtProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link InternalJwtIssuer}: tokens the FastAPI service must be able to
 * verify — HS256, audience-restricted, short-lived — and a secret too weak
 * for HS256 refuses to start rather than signing with it.
 */
class InternalJwtIssuerTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes";

    @Test
    void issue_producesAudienceRestrictedTokenVerifiableWithTheSharedSecret() throws Exception {
        InternalJwtIssuer issuer = new InternalJwtIssuer(properties(SECRET));

        // Verified with Nimbus directly, the way any HS256 verifier (e.g. PyJWT in the FastAPI
        // service) would; Spring's decoder insists `iss` is a URL, which the contract doesn't require.
        SignedJWT jwt = SignedJWT.parse(issuer.issue());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly("fastapi-ai-service");
        assertThat(claims.getIssuer()).isEqualTo("trace-orchestrator");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void issue_tokenSignedWithADifferentSecret_failsVerification() throws Exception {
        SignedJWT jwt = SignedJWT.parse(new InternalJwtIssuer(properties(SECRET)).issue());

        assertThat(jwt.verify(new MACVerifier("another-secret-that-is-also-32-bytes!!".getBytes(StandardCharsets.UTF_8))))
                .isFalse();
    }

    @Test
    void issue_mintsAFreshTokenEachCall() {
        InternalJwtIssuer issuer = new InternalJwtIssuer(properties(SECRET));

        assertThat(issuer.issue()).isNotEqualTo(issuer.issue());
    }

    @Test
    void constructor_rejectsSecretShorterThanHs256Minimum_withoutEchoingIt() {
        String weak = "too-short-secret";

        assertThatThrownBy(() -> new InternalJwtIssuer(properties(weak)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes")
                .hasMessageNotContaining(weak);
    }

    @Test
    void constructor_rejectsMissingSecret() {
        assertThatThrownBy(() -> new InternalJwtIssuer(properties(null)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static InternalJwtProperties properties(String secret) {
        InternalJwtProperties properties = new InternalJwtProperties();
        properties.setSecret(secret);
        return properties;
    }
}
