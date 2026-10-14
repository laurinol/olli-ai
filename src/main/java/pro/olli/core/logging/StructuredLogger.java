/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.core.logging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * Enhanced logger with structured logging capabilities for better traceability.
 * Supports context tracking via MDC for correlating logs across calls.
 */
@ApplicationScoped
public class StructuredLogger {

    private static final String TRACE_ID = "traceId";
    private static final String SPAN_ID = "spanId";
    private static final String BUSINESS_ID = "businessId";
    private static final String USER_ID = "userId";
    
    private final Logger log;

    @Inject
    public StructuredLogger() {
        this.log = Logger.getLogger(StructuredLogger.class);
    }
    
    /**
     * Starts a new logging context with a generated trace ID.
     * 
     * @return The generated trace ID
     */
    public String startContext() {
        // Get current span from OpenTelemetry context
        Span currentSpan = Span.current();
        SpanContext spanContext = currentSpan.getSpanContext();
        
        String traceId = spanContext.isValid() ? 
            spanContext.getTraceId() : generateTraceId();
        String spanId = spanContext.isValid() ?
            spanContext.getSpanId() : generateTraceId();
            
        MDC.put(TRACE_ID, traceId);
        MDC.put(SPAN_ID, spanId);
        return traceId;
    }
    
    /**
     * Starts a new logging context with all the provided information.
     * 
     * @param businessId The business ID
     * @param userId The user ID
     * @return The generated trace ID
     */
    public String startContext(String businessId, String userId) {
        // Get current span from OpenTelemetry context
        Span currentSpan = Span.current();
        SpanContext spanContext = currentSpan.getSpanContext();
        
        String traceId = spanContext.isValid() ? 
            spanContext.getTraceId() : generateTraceId();
        String spanId = spanContext.isValid() ?
            spanContext.getSpanId() : generateTraceId();
            
        MDC.put(TRACE_ID, traceId);
        MDC.put(SPAN_ID, spanId);
        MDC.put(BUSINESS_ID, businessId);
        MDC.put(USER_ID, userId);
        
        // Add business/user IDs as attributes to the current span for better context
        if (spanContext.isValid()) {
            currentSpan.setAttribute("business.id", businessId);
            currentSpan.setAttribute("user.id", userId);
        }
        
        return traceId;
    }
    
    /**
     * Ends the current logging context by clearing MDC.
     */
    public void endContext() {
        MDC.clear();
    }
    
    /**
     * Logs application metrics for monitoring and alerting.
     * 
     * @param metricName The name of the metric
     * @param value The value of the metric
     * @param attributes Additional attributes to include
     */
    public void metric(String metricName, Number value, Map<String, Object> attributes) {
        Map<String, Object> enrichedAttrs = addContextToMap(attributes);
        enrichedAttrs.put("metricName", metricName);
        enrichedAttrs.put("metricValue", value);
        log.infof("METRIC %s=%s %s", metricName, value, enrichedAttrs);
    }
    
    /**
     * Logs an important event for auditing purposes.
     * 
     * @param eventName The name of the event
     * @param properties Additional properties to include
     */
    public void event(String eventName, Map<String, Object> properties) {
        Map<String, Object> enrichedProps = addContextToMap(properties);
        enrichedProps.put("eventName", eventName);
        log.infof("EVENT %s %s", eventName, enrichedProps);
    }
    
    /**
     * Logs a detailed debug message with context.
     * 
     * @param message The message to log
     * @param data Additional data to include
     */
    public void debug(String message, Map<String, Object> data) {
        if (!log.isDebugEnabled()) {
            return;
        }
        Map<String, Object> enrichedData = addContextToMap(data);
        log.debugf("%s %s", message, enrichedData);
    }
    
    /**
     * Logs an informational message with context.
     * 
     * @param message The message to log
     * @param data Additional data to include
     */
    public void info(String message, Map<String, Object> data) {
        Map<String, Object> enrichedData = addContextToMap(data);
        log.infof("%s %s", message, enrichedData);
    }
    
    /**
     * Logs a warning message with context.
     * 
     * @param message The message to log
     * @param data Additional data to include
     */
    public void warn(String message, Map<String, Object> data) {
        Map<String, Object> enrichedData = addContextToMap(data);
        log.warnf("%s %s", message, enrichedData);
    }
    
    /**
     * Logs an error message with context.
     * 
     * @param message The message to log
     * @param error The exception that occurred
     * @param data Additional data to include
     */
    public void error(String message, Throwable error, Map<String, Object> data) {
        Map<String, Object> enrichedData = addContextToMap(data);
        enrichedData.put("exceptionClass", error.getClass().getName());
        enrichedData.put("exceptionMessage", error.getMessage());
        log.errorf(error, "%s %s", message, enrichedData);
    }
    
    private Map<String, Object> addContextToMap(Map<String, Object> data) {
        // Create a new mutable map instead of using Map.of() which is immutable
        Map<String, Object> result = new java.util.HashMap<>();
        
        // Copy original data if provided
        if (data != null) {
            result.putAll(data);
        }
        
        // Add MDC context values if present
        Object traceIdObj = MDC.get(TRACE_ID);
        if (traceIdObj != null && !result.containsKey(TRACE_ID)) {
            result.put(TRACE_ID, traceIdObj.toString());
        }
        
        Object businessIdObj = MDC.get(BUSINESS_ID);
        if (businessIdObj != null && !result.containsKey(BUSINESS_ID)) {
            result.put(BUSINESS_ID, businessIdObj.toString());
        }
        
        Object userIdObj = MDC.get(USER_ID);
        if (userIdObj != null && !result.containsKey(USER_ID)) {
            result.put(USER_ID, userIdObj.toString());
        }
        
        return result;
    }
    
    private String generateTraceId() {
        return UUID.randomUUID().toString();
    }
}
