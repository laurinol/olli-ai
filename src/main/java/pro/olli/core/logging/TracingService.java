/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.core.logging;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Service for manual tracing management and context propagation
 * across service boundaries and asynchronous operations.
 */
@ApplicationScoped
public class TracingService {

    private final Tracer tracer;

    @Inject
    public TracingService(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("pro.olli.core.logging");
    }

    /**
     * Creates and executes a new span for an operation.
     *
     * @param spanName     The name of the span
     * @param operation    The operation to execute within the span
     * @param attributes   Additional attributes to add to the span
     * @param <T>          The return type of the operation
     * @return The result of the operation
     */
    public <T> T withSpan(String spanName, Supplier<T> operation, Map<String, String> attributes) {
        SpanBuilder spanBuilder = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL);
        
        if (attributes != null) {
            attributes.forEach(spanBuilder::setAttribute);
        }
        
        Span span = spanBuilder.startSpan();
        try (Scope scope = span.makeCurrent()) {
            return operation.get();
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
    
    /**
     * Creates and executes a new span for a client call.
     *
     * @param spanName     The name of the span
     * @param operation    The operation to execute within the span
     * @param attributes   Additional attributes to add to the span
     * @param <T>          The return type of the operation
     * @return The result of the operation
     */
    public <T> T withClientSpan(String spanName, Supplier<T> operation, Map<String, String> attributes) {
        SpanBuilder spanBuilder = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT);
        
        if (attributes != null) {
            attributes.forEach(spanBuilder::setAttribute);
        }
        
        Span span = spanBuilder.startSpan();
        try (Scope scope = span.makeCurrent()) {
            return operation.get();
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
    
    /**
     * Adds the current tracing context to an asynchronous operation.
     * This is used to maintain trace continuity across async boundaries
     * like thread pools, callbacks, etc.
     *
     * @param operation The operation to wrap with the current context
     * @param <T>       The input type of the operation
     * @param <R>       The return type of the operation
     * @return The operation wrapped with the current context
     */
    public <T, R> Function<T, R> withContext(Function<T, R> operation) {
        Context context = Context.current();
        return input -> {
            try (Scope scope = context.makeCurrent()) {
                return operation.apply(input);
            }
        };
    }
    
    /**
     * Gets the current trace ID, useful for logging
     * @return The current trace ID or null if not available
     */
    public String getCurrentTraceId() {
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            return span.getSpanContext().getTraceId();
        }
        return null;
    }
    
    /**
     * Gets the current span ID, useful for logging
     * @return The current span ID or null if not available
     */
    public String getCurrentSpanId() {
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            return span.getSpanContext().getSpanId();
        }
        return null;
    }
    
    /**
     * Adds an event to the current span
     * @param name The event name
     * @param attributes Additional attributes for the event
     */
    public void addEvent(String name, Map<String, String> attributes) {
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            if (attributes != null) {
                io.opentelemetry.api.common.AttributesBuilder builder = io.opentelemetry.api.common.Attributes.builder();
                attributes.forEach(builder::put);
                span.addEvent(name, builder.build());
            } else {
                span.addEvent(name);
            }
        }
    }
}
