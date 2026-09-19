package com.trace.orchestrator.config;

import com.trace.orchestrator.constant.ApiConstants;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Placeholder security configuration. Inbound authentication (validating the
 * JWT a caller presents) isn't implemented yet — see the root
 * {@code CLAUDE.md}'s security plan — so this permits every request rather
 * than leaving Spring Security's browser-oriented defaults (session CSRF,
 * login page, HTTP Basic challenge) in place for what is meant to be a
 * stateless JSON API. CSRF is disabled for the same reason: it protects
 * cookie/session-authenticated browser clients, not a header-authenticated
 * API. CORS is enabled so local tooling (Postman, a dev frontend) can call
 * this API directly. Replace this with real JWT validation when that task
 * is implemented.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // Custom response headers are hidden from browser JS unless exposed.
        configuration.setExposedHeaders(List.of(ApiConstants.IDEMPOTENT_REPLAYED_HEADER));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
