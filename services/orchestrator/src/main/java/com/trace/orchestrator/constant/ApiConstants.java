package com.trace.orchestrator.constant;

/**
 * Constants for the HTTP-facing layer — controller paths and headers, the
 * trace-id plumbing, request-validation patterns, and the {@code error}
 * codes in the standard error envelope. Not instantiable.
 *
 * <p>Service-layer constants live in {@link ServiceConstants}.
 */
public final class ApiConstants {

    private ApiConstants() {
    }

    // ---- Paths -------------------------------------------------------------

    public static final String EXCEPTIONS_BASE_PATH = "/api/v1/exceptions";
    public static final String EXCEPTIONS_CREATE_PATH = "/create";
    public static final String INVESTIGATION_LINK_SUFFIX = "/investigation";

    /** The FastAPI AI Service's investigation endpoint (contracts/openapi/internal-api.yaml). */
    public static final String ENGINE_INVESTIGATE_PATH = "/internal/v1/investigate";

    // ---- Headers -----------------------------------------------------------

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";
    public static final String IDEMPOTENT_REPLAYED_VALUE = "true";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    // ---- Trace id ----------------------------------------------------------

    /** Request attribute set by {@code TraceIdFilter} and read by the error handler. */
    public static final String TRACE_ID_ATTRIBUTE = "traceId";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    // ---- Request validation ------------------------------------------------

    /** invoice_id / po_id / vendor_id format (TDD §3.3). */
    public static final String ID_PATTERN = "^[A-Z]{2,4}-\\d{4,8}$";

    // ---- Error envelope `error` codes (TDD §3.4) ----------------------------

    public static final String ERROR_VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String ERROR_UNPROCESSABLE_REFERENCE = "UNPROCESSABLE_REFERENCE";
    public static final String ERROR_IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String ERROR_REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS";
    public static final String ERROR_INTERNAL = "INTERNAL_ERROR";
}
