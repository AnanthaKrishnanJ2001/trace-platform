package com.trace.orchestrator.security;

import com.trace.orchestrator.constant.ApiConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Generates the {@code trace_id} for every inbound request, once, at this
 * gateway (root {@code CLAUDE.md}) — stashes it as a request attribute (for
 * {@link com.trace.orchestrator.exception.GlobalExceptionHandler} and future
 * downstream calls to propagate as {@code X-Trace-Id}) and in the logging
 * MDC so both services' logs can be joined for one request, and echoes it
 * back as a response header for client-side correlation.
 */
@Component
@Order(1)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute(ApiConstants.TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(ApiConstants.TRACE_ID_HEADER, traceId);
        MDC.put(ApiConstants.TRACE_ID_MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ApiConstants.TRACE_ID_MDC_KEY);
        }
    }
}
