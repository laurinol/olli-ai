/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.core.metrics;

import io.quarkus.arc.ArcInvocationContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * Interceptor that processes the @Timed annotation and measures method execution time.
 */
@Timed
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class TimedInterceptor {

    private static final Logger LOG = Logger.getLogger(TimedInterceptor.class);

    private final PerformanceMetrics metrics;

    @Inject
    public TimedInterceptor(PerformanceMetrics metrics) {
        this.metrics = metrics;
    }

    @AroundInvoke
    public Object timeMethod(InvocationContext context) throws Exception {
        // Get the annotation
        Timed timed = getTimedAnnotation(context);
        if (timed == null) {
            // This shouldn't happen, but just in case
            return context.proceed();
        }

        // Get the metric name
        String metricName = getMetricName(timed, context);

        // Get context tags from MDC and method arguments
        Map<String, String> tags = getContextTags(context);

        // Start the timer
        String operationId = metrics.startTimer(metricName, null, tags);

        try {
            // Execute the method
            Object result = context.proceed();

            // Stop the timer with success status
            metrics.stopTimer(metricName, operationId, tags, PerformanceMetrics.OperationStatus.SUCCESS);

            return result;
        } catch (Exception e) {
            // Stop the timer with failure status
            PerformanceMetrics.OperationStatus status = getErrorStatus(e);
            metrics.stopTimer(metricName, operationId, tags, status);

            // Re-throw the exception
            throw e;
        }
    }

    /**
     * Gets the Timed annotation from the method or class
     */
    private Timed getTimedAnnotation(InvocationContext context) {
        // Try to get it from the method first
        if (context instanceof ArcInvocationContext) {
            ArcInvocationContext arcContext = (ArcInvocationContext) context;
            Timed timed = arcContext.getMethod().getAnnotation(Timed.class);
            if (timed != null) {
                return timed;
            }
        }

        // Then check the class
        return context.getTarget().getClass().getAnnotation(Timed.class);
    }

    /**
     * Gets the metric name from the annotation or generates one from the method and class
     */
    private String getMetricName(Timed timed, InvocationContext context) {
        // If provided in the annotation, use that
        if (timed.value() != null && !timed.value().isEmpty()) {
            return timed.value();
        }

        // Otherwise, build one from the operation type and method info
        String opTypePrefix = getOperationTypePrefix(timed.type());

        if (context instanceof ArcInvocationContext) {
            ArcInvocationContext arcContext = (ArcInvocationContext) context;
            String className = arcContext.getTarget().getClass().getSimpleName();
            String methodName = arcContext.getMethod().getName();
            return opTypePrefix + "." + className + "." + methodName;
        }

        // Fallback to a generic name
        return opTypePrefix + ".operation";
    }

    /**
     * Gets the operation type prefix based on the operation type
     */
    private String getOperationTypePrefix(Timed.OperationType type) {
        return switch (type) {
            case DATABASE -> PerformanceMetrics.METRIC_DB_OPERATION_DURATION;
            case CONNECTOR -> PerformanceMetrics.METRIC_CONNECTOR_OPERATION_DURATION;
            case AI_SERVICE -> PerformanceMetrics.METRIC_AI_REQUEST_DURATION;
            case API_CALL -> PerformanceMetrics.METRIC_API_REQUEST_DURATION;
            case SYNC_TASK -> PerformanceMetrics.METRIC_SYNC_TASK_DURATION;
            default -> "operation.duration";
        };
    }

    /**
     * Extracts context tags from MDC and method arguments
     */
    private Map<String, String> getContextTags(InvocationContext context) {
        Map<String, String> tags = new HashMap<>();

        // Try to get tags from MDC for correlation
        String traceId = (String) MDC.get("traceId");
        if (traceId != null) {
            tags.put("traceId", traceId);
        }

        String businessId = (String) MDC.get("businessId");
        if (businessId != null) {
            tags.put("businessId", businessId);
        }

        String userId = (String) MDC.get("userId");
        if (userId != null) {
            tags.put("userId", userId);
        }

        // Add the class and method name as tags
        if (context instanceof ArcInvocationContext) {
            ArcInvocationContext arcContext = (ArcInvocationContext) context;
            String className = arcContext.getTarget().getClass().getSimpleName();
            String methodName = arcContext.getMethod().getName();
            tags.put("class", className);
            tags.put("method", methodName);
        }

        return tags;
    }

    /**
     * Determines the error status from the exception type
     */
    private PerformanceMetrics.OperationStatus getErrorStatus(Exception e) {
        if (e.getClass().getName().contains("Timeout")) {
            return PerformanceMetrics.OperationStatus.TIMEOUT;
        } else {
            return PerformanceMetrics.OperationStatus.FAILURE;
        }
    }
}
