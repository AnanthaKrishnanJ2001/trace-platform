package com.trace.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trace.orchestrator.config.AiServiceProperties;
import com.trace.orchestrator.config.InternalJwtProperties;
import com.trace.orchestrator.dto.InvestigationRequest;
import com.trace.orchestrator.dto.InvestigationResult;
import com.trace.orchestrator.exception.InvestigationUnavailableException;
import com.trace.orchestrator.security.InternalJwtIssuer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * {@link InvestigationClient} against a real local HTTP server (JDK
 * {@code HttpServer}, no extra dependency): what goes on the wire, and that
 * every failure mode surfaces as {@link InvestigationUnavailableException}.
 */
class InvestigationClientTest {

    private static final String SUCCESS_BODY = """
            {"investigation_id":"INVST-1","status":"COMPLETED",
             "root_cause":{"root_cause_label":"PRICE_VARIANCE","confidence":92,"evidence":["INV-10493"]},
             "unexpected_new_field":true}""";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final CopyOnWriteArrayList<String> authHeaders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> traceHeaders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> requestBodies = new CopyOnWriteArrayList<>();
    private volatile Handler handler = exchange -> respond(exchange, 200, SUCCESS_BODY);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            traceHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Trace-Id")));
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.handle(exchange);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void investigate_sendsJwtTraceIdAndContractShapedBody_andParsesResult() {
        InvestigationResult result = client(3, 100).investigate(request(), "trace-42");

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.rootCause().rootCauseLabel()).isEqualTo("PRICE_VARIANCE");
        assertThat(result.rootCause().confidence()).isEqualTo(92);
        assertThat(authHeaders.get(0)).startsWith("Bearer ey");
        assertThat(traceHeaders.get(0)).isEqualTo("trace-42");
        assertThat(requestBodies.get(0))
                .contains("\"investigation_id\":\"INVST-1\"")
                .contains("\"exception_id\":\"EXC-00011\"")
                .contains("\"exception_type\":\"PO_INVOICE_MISMATCH\"")
                .contains("\"invoice_id\":\"INV-10493\"")
                .contains("\"confidence_threshold\":70")
                .doesNotContain("callback_url");
    }

    @Test
    void investigate_retriesServerErrors_thenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        handler = exchange -> {
            if (calls.incrementAndGet() < 3) {
                respond(exchange, 503, "{}");
            } else {
                respond(exchange, 200, SUCCESS_BODY);
            }
        };

        InvestigationResult result = client(3, 100).investigate(request(), "t");

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(hits.get()).isEqualTo(3);
    }

    @Test
    void investigate_persistentServerError_isUnavailableAfterRetriesExhausted() {
        handler = exchange -> respond(exchange, 500, "{}");

        assertThatThrownBy(() -> client(3, 100).investigate(request(), "t"))
                .isInstanceOf(InvestigationUnavailableException.class);
        assertThat(hits.get()).isEqualTo(3);
    }

    @Test
    void investigate_clientError_isNotRetried() {
        handler = exchange -> respond(exchange, 401, "{}");

        assertThatThrownBy(() -> client(3, 100).investigate(request(), "t"))
                .isInstanceOf(InvestigationUnavailableException.class)
                .hasCauseInstanceOf(HttpClientErrorException.class);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void investigate_slowEngine_timesOutAsUnavailable() {
        handler = exchange -> {
            sleep(1500);
            respond(exchange, 200, SUCCESS_BODY);
        };

        assertThatThrownBy(() -> client(1, 300).investigate(request(), "t"))
                .isInstanceOf(InvestigationUnavailableException.class);
    }

    @Test
    void investigate_afterRepeatedFailures_breakerOpensAndFailsFastWithoutHittingEngine() {
        handler = exchange -> respond(exchange, 500, "{}");
        InvestigationClient client = client(1, 100, 2);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.investigate(request(), "t"))
                    .isInstanceOf(InvestigationUnavailableException.class);
        }
        int hitsBeforeOpen = hits.get();

        assertThatThrownBy(() -> client.investigate(request(), "t"))
                .isInstanceOf(InvestigationUnavailableException.class)
                .hasMessageContaining("circuit breaker is open")
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThat(hits.get()).isEqualTo(hitsBeforeOpen);
    }

    @Test
    void investigate_engineNotListening_isUnavailable() {
        InvestigationClient client = client(1, 100);
        server.stop(0);

        assertThatThrownBy(() -> client.investigate(request(), "t"))
                .isInstanceOf(InvestigationUnavailableException.class);
    }

    private InvestigationClient client(int retryAttempts, long readTimeoutMs) {
        return client(retryAttempts, readTimeoutMs, 50);
    }

    /** {@code breakerWindow} calls must be seen before the breaker can open, so retry tests use a large one. */
    private InvestigationClient client(int retryAttempts, long readTimeoutMs, int breakerWindow) {
        AiServiceProperties properties = new AiServiceProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeout(Duration.ofMillis(500));

        InternalJwtProperties jwtProperties = new InternalJwtProperties();
        jwtProperties.setSecret("unit-test-secret-that-is-at-least-32-bytes");

        // Mirrors the investigationService instance in application.yml, sped up for tests.
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(breakerWindow).minimumNumberOfCalls(breakerWindow).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1)).build());
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(retryAttempts).waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(CallNotPermittedException.class, HttpClientErrorException.class).build());
        TimeLimiterRegistry timeLimiters = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(readTimeoutMs)).build());

        return new InvestigationClient(RestClient.builder(), properties, new InternalJwtIssuer(jwtProperties),
                breakers, retries, timeLimiters);
    }

    private static InvestigationRequest request() {
        return new InvestigationRequest("INVST-1", "EXC-00011", "PO_INVOICE_MISMATCH",
                new InvestigationRequest.References("INV-10493", "PO-7822", "VEND-3391"),
                new InvestigationRequest.Context(70));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
