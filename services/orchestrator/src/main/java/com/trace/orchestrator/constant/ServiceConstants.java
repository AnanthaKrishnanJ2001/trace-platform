package com.trace.orchestrator.constant;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Constants used by the classes in {@code com.trace.orchestrator.service},
 * grouped by the service that owns them. Not instantiable.
 */
public final class ServiceConstants {

    private ServiceConstants() {
    }

    // ---- ExceptionService -------------------------------------------------

    public static final String EXCEPTION_ID_PREFIX = "EXC-";
    public static final int EXCEPTION_ID_MIN_DIGITS = 5;
    public static final String STATUS_DETECTED = "DETECTED";

    // ---- ExceptionClassifier ----------------------------------------------

    public static final String TYPE_PO_INVOICE_MISMATCH = "PO_INVOICE_MISMATCH";

    public static final String SEVERITY_LOW = "LOW";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_CRITICAL = "CRITICAL";

    public static final int DEVIATION_SCALE = 4;
    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // ---- ExceptionIngestionService ----------------------------------------

    /** Same rule as the contract's {@code Idempotency-Key} parameter. */
    public static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    // ---- IdempotencyStore -------------------------------------------------

    /** Attempts to claim a key that expires between the failed SET NX and the follow-up GET. */
    public static final int MAX_CLAIM_ATTEMPTS = 2;
}
