/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.core.logging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * Request logging filter for traceability and monitoring.
 * Adds trace IDs to all requests and logs request/response details.
 */
@Provider
@Priority(Priorities.USER)
public class RequestLoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String TRACE_ID = "traceId";
    private static final String SPAN_ID = "spanId";
    private static final String REQUEST_START_TIME = "requestStartTime";

    @Inject
    StructuredLogger logger;

    @Inject
    TracingService tracingService;

    @Context
    UriInfo uriInfo;

    @Context
    SecurityContext securityContext;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // Get current span from OpenTelemetry context (automatically created for HTTP requests)
        Span currentSpan = Span.current();
        SpanContext spanContext = currentSpan.getSpanContext();
        
        // Get or generate trace ID and span ID
        String traceId;
        String spanId;
        
        if (spanContext.isValid()) {
            traceId = spanContext.getTraceId();
            spanId = spanContext.getSpanId();
        } else {
            // Fallback if OpenTelemetry is not configured properly
            traceId = requestContext.getHeaderString("X-Trace-ID");
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }
            spanId = UUID.randomUUID().toString().substring(0, 16);
        }

        // Store in MDC for logging context
        MDC.put(TRACE_ID, traceId);
        MDC.put(SPAN_ID, spanId);
        requestContext.setProperty(TRACE_ID, traceId);
        requestContext.setProperty(SPAN_ID, spanId);
        requestContext.setProperty(REQUEST_START_TIME, System.currentTimeMillis());

        // Add user info if available
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            String userId = securityContext.getUserPrincipal().getName();
            MDC.put("userId", userId);
            requestContext.setProperty("userId", userId);
            
            // Add to span for better context
            if (spanContext.isValid()) {
                currentSpan.setAttribute("user.id", userId);
            }
        }
        
        // Add HTTP request details to the span
        if (spanContext.isValid()) {
            currentSpan.setAttribute("http.method", requestContext.getMethod());
            currentSpan.setAttribute("http.path", uriInfo.getPath());
            
            String clientIp = requestContext.getHeaderString("X-Forwarded-For");
            if (clientIp != null && !clientIp.isBlank()) {
                currentSpan.setAttribute("http.client_ip", clientIp);
            }
            
            // Add business context if available
            String businessId = requestContext.getHeaderString("X-Business-ID");
            if (businessId != null && !businessId.isBlank()) {
                currentSpan.setAttribute("business.id", businessId);
                MDC.put("businessId", businessId);
                requestContext.setProperty("businessId", businessId);
            }
        }

        // Log the request
        logger.info("API Request", Map.of(
            "method", requestContext.getMethod(),
            "path", uriInfo.getPath(),
            "traceId", traceId,
            "spanId", spanId,
            "remoteAddr", requestContext.getHeaderString("X-Forwarded-For") != null ?
                requestContext.getHeaderString("X-Forwarded-For") :
                "direct-call"
        ));
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String traceId = (String) requestContext.getProperty(TRACE_ID);
        String spanId = (String) requestContext.getProperty(SPAN_ID);
        Long startTime = (Long) requestContext.getProperty(REQUEST_START_TIME);
        Long duration = startTime != null ? System.currentTimeMillis() - startTime : null;

        // Get the current span (should be the same as in the request filter)
        Span currentSpan = Span.current();
        
        // Add response details to span
        if (currentSpan.getSpanContext().isValid()) {
            currentSpan.setAttribute("http.status_code", responseContext.getStatus());
            
            if (duration != null) {
                currentSpan.setAttribute("http.duration_ms", duration);
            }
            
            // Mark error if status code is 4xx or 5xx
            int statusCode = responseContext.getStatus();
            if (statusCode >= 400) {
                currentSpan.setAttribute("error", true);
                currentSpan.setAttribute("error.type", statusCode >= 500 ? "server_error" : "client_error");
                
                // Add error details
                Object entity = responseContext.getEntity();
                if (entity != null && entity.toString().length() < 1000) {
                    currentSpan.setAttribute("error.details", entity.toString());
                }
            }
        }

        // Log the response
        logger.info("API Response", Map.of(
            "method", requestContext.getMethod(),
            "path", uriInfo.getPath(),
            "traceId", traceId != null ? traceId : "unknown",
            "spanId", spanId != null ? spanId : "unknown",
            "statusCode", responseContext.getStatus(),
            "durationMs", duration != null ? duration : -1
        ));

        // Log metrics for monitoring
        if (duration != null) {
            logger.metric("api.request.duration", duration, Map.of(
                "method", requestContext.getMethod(),
                "path", uriInfo.getPath(),
                "statusCode", responseContext.getStatus()
            ));
        }

        // Clear MDC at the end of the request
        MDC.clear();
    }
}
