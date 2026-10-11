package com.trace.orchestrator.client;

import com.trace.orchestrator.config.AiServiceProperties;
import com.trace.orchestrator.constant.ApiConstants;
import com.trace.orchestrator.constant.ServiceConstants;
import com.trace.orchestrator.dto.InvestigationRequest;
import com.trace.orchestrator.dto.InvestigationResult;
import com.trace.orchestrator.exception.InvestigationUnavailableException;
import com.trace.orchestrator.security.InternalJwtIssuer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the FastAPI AI Service's {@code POST /internal/v1/investigate}
 * (contracts/openapi/internal-api.yaml). Every call carries the internal JWT
 * and the request's {@code X-Trace-Id} (root {@code CLAUDE.md}), and goes
 * through the existing {@code investigationService} Resilience4j instance —
 * retry around circuit breaker, so an open breaker fails the whole call fast.
 * The read timeout is the instance's time-limiter duration (8s), so a slow
 * Engine can't hold a worker thread past the budget.
 *
 * <p>Any failure — open breaker, timeout, connection error, non-2xx — is
 * surfaced as {@link InvestigationUnavailableException}.
 */
@Component
public class InvestigationClient {

    private static final Logger log = LoggerFactory.getLogger(InvestigationClient.class);

    private final RestClient restClient;
    private final InternalJwtIssuer jwtIssuer;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public InvestigationClient(RestClient.Builder restClientBuilder, AiServiceProperties properties,
            InternalJwtIssuer jwtIssuer, CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry) {
        Duration readTimeout = timeLimiterRegistry.timeLimiter(ServiceConstants.INVESTIGATION_RESILIENCE_INSTANCE)
                .getTimeLimiterConfig().getTimeoutDuration();
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(properties.getConnectTimeout())
                        .withReadTimeout(readTimeout)))
                .build();
        this.jwtIssuer = jwtIssuer;
        this.circuitBreaker = circuitBreakerRegistry
                .circuitBreaker(ServiceConstants.INVESTIGATION_RESILIENCE_INSTANCE);
        this.retry = retryRegistry.retry(ServiceConstants.INVESTIGATION_RESILIENCE_INSTANCE);
    }

    /**
     * @param traceId the originating request's trace id, sent as {@code X-Trace-Id}
     * @throws InvestigationUnavailableException if the Engine could not answer
     */
    public InvestigationResult investigate(InvestigationRequest request, String traceId) {
        Supplier<InvestigationResult> call = () -> post(request, traceId);
        Supplier<InvestigationResult> guarded = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, call));
        try {
            return guarded.get();
        } catch (CallNotPermittedException ex) {
            throw new InvestigationUnavailableException("AI service circuit breaker is open.", ex);
        } catch (RestClientException ex) {
            throw new InvestigationUnavailableException("AI service call failed: " + ex.getMessage(), ex);
        }
    }

    private InvestigationResult post(InvestigationRequest request, String traceId) {
        log.debug("Calling AI service to investigate {} (trace_id={})", request.exceptionId(), traceId);
        InvestigationResult result = restClient.post()
                .uri(ApiConstants.ENGINE_INVESTIGATE_PATH)
                .headers(headers -> {
                    headers.setBearerAuth(jwtIssuer.issue());
                    headers.set(ApiConstants.TRACE_ID_HEADER, traceId);
                })
                .body(request)
                .retrieve()
                .body(InvestigationResult.class);
        if (result == null) {
            throw new InvestigationUnavailableException("AI service returned an empty body", null);
        }
        return result;
    }
}
