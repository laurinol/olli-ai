/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.core.metrics;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tag;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics service for tracking application performance metrics.
 * Designed to work with multiple monitoring systems including Prometheus, GCP Cloud Monitoring,
 * AWS CloudWatch, and Azure Monitor.
 */
@ApplicationScoped
public class PerformanceMetrics {

    private static final Logger LOG = Logger.getLogger(PerformanceMetrics.class);

    // Constants for metric names - these will be consistent across monitoring systems
    public static final String METRIC_API_REQUEST_DURATION = "api.request.duration";
    public static final String METRIC_DB_OPERATION_DURATION = "db.operation.duration";
    public static final String METRIC_CONNECTOR_OPERATION_DURATION = "connector.operation.duration";
    public static final String METRIC_AI_REQUEST_DURATION = "ai.request.duration";
    public static final String METRIC_SYNC_TASK_DURATION = "sync.task.duration";
    public static final String METRIC_CIRCUIT_BREAKER_OPEN = "circuit.breaker.open";
    public static final String METRIC_RETRY_ATTEMPTS = "retry.attempts";
    public static final String METRIC_SYNC_TASK_FILES_PROCESSED = "sync.task.files.processed";

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicLong> gaugeValues = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Instant>> timerStartTimes = new ConcurrentHashMap<>();

    @Inject
    public PerformanceMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Starts timing an operation that will be measured
     *
     * @param metricName The name of the metric
     * @param operationId A unique ID for this operation instance
     * @param tags Additional context tags
     * @return The operation ID for use with stopTimer
     */
    public String startTimer(String metricName, String operationId, Map<String, String> tags) {
        String opId = operationId != null ? operationId : generateId(metricName);
        Map<String, Instant> timers = timerStartTimes.computeIfAbsent(metricName, k -> new ConcurrentHashMap<>());
        timers.put(opId, Instant.now());

        // Increment operation counter
        List<Tag> micrometerTags = createTags(tags);
        micrometerTags.add(Tag.of("operation", opId));
        Counter.builder(metricName + ".started")
               .tags(micrometerTags)
               .register(meterRegistry)
               .increment();

        return opId;
    }

    /**
     * Stops timing an operation and records the duration
     *
     * @param metricName The name of the metric
     * @param operationId The operation ID from startTimer
     * @param tags Additional context tags
     * @param status Success or failure status
     * @return The duration in milliseconds, or -1 if the timer wasn't found
     */
    public long stopTimer(String metricName, String operationId, Map<String, String> tags, OperationStatus status) {
        Map<String, Instant> timers = timerStartTimes.get(metricName);
        if (timers == null || !timers.containsKey(operationId)) {
            LOG.warnf("No timer found for metric %s and operation %s", metricName, operationId);
            return -1;
        }

        Instant startTime = timers.remove(operationId);
        long durationMs = Duration.between(startTime, Instant.now()).toMillis();

        // We'll add tags for operation success/failure
        String statusTag = status.name().toLowerCase();
        List<Tag> micrometerTags = createTags(tags);
        micrometerTags.add(Tag.of("operation", operationId));
        micrometerTags.add(Tag.of("status", statusTag));

        // Record the duration
        meterRegistry.timer(metricName, micrometerTags)
                     .record(Duration.ofMillis(durationMs));

        // Increment completed counter
        Counter.builder(metricName + ".completed")
               .tags(micrometerTags)
               .register(meterRegistry)
               .increment();

        return durationMs;
    }

    /**
     * Records a simple counter metric
     *
     * @param metricName The name of the metric
     * @param tags Additional context tags
     */
    public void incrementCounter(String metricName, Map<String, String> tags) {
        List<Tag> micrometerTags = createTags(tags);
        Counter.builder(metricName)
               .tags(micrometerTags)
               .register(meterRegistry)
               .increment();
    }

    /**
     * Records a gauge value
     *
     * @param metricName The name of the metric
     * @param value The value to record
     * @param tags Additional context tags
     */
    public void recordGauge(String metricName, long value, Map<String, String> tags) {
        List<Tag> micrometerTags = createTags(tags);
        String fullName = metricName + "." + formatTagsForName(tags);

        // Get or create the gauge value
        AtomicLong gaugeValue = gaugeValues.computeIfAbsent(fullName, k -> {
            AtomicLong newGaugeValue = new AtomicLong(0);
            Gauge.builder(metricName, newGaugeValue, AtomicLong::get)
                 .tags(micrometerTags)
                 .register(meterRegistry);
            return newGaugeValue;
        });

        gaugeValue.set(value);
    }

    /**
     * Records a histogram value
     *
     * @param metricName The name of the metric
     * @param value The value to record
     * @param tags Additional context tags
     */
    public void recordHistogram(String metricName, long value, Map<String, String> tags) {
        List<Tag> micrometerTags = createTags(tags);
        meterRegistry.summary(metricName, micrometerTags).record(value);
    }

    /**
     * Records a timed operation in a single call
     *
     * @param metricName The name of the metric
     * @param durationMs The duration in milliseconds
     * @param tags Additional context tags
     * @param status Success or failure status
     */
    public void recordTiming(String metricName, long durationMs, Map<String, String> tags, OperationStatus status) {
        String statusTag = status.name().toLowerCase();
        List<Tag> micrometerTags = createTags(tags);
        micrometerTags.add(Tag.of("status", statusTag));

        // Record the duration
        meterRegistry.timer(metricName, micrometerTags)
                     .record(Duration.ofMillis(durationMs));
    }

    /**
     * Records data for circuit breaker state - useful for tracking when circuit breakers open
     *
     * @param circuitName The name of the circuit breaker
     * @param isOpen Whether the circuit breaker is open
     * @param tags Additional context tags
     */
    public void recordCircuitBreakerState(String circuitName, boolean isOpen, Map<String, String> tags) {
        Map<String, String> allTags = tags != null ? new HashMap<>(tags) : new HashMap<>();
        allTags.put("circuitName", circuitName);

        String tagString = formatTagsForName(allTags);
        recordGauge(METRIC_CIRCUIT_BREAKER_OPEN, isOpen ? 1 : 0, allTags);

        if (isOpen) {
            LOG.warnf("Circuit breaker %s is now OPEN %s", circuitName, tagString);
        } else {
            LOG.infof("Circuit breaker %s is now CLOSED %s", circuitName, tagString);
        }
    }

    /**
     * Records the number of files processed in a sync operation
     *
     * @param businessId The business ID
     * @param userId The user ID
     * @param source The source connector provider
     * @param count The number of files processed
     */
    public void recordFilesProcessed(String businessId, String userId, String source, int count) {
        Map<String, String> tags = new HashMap<>();
        tags.put("businessId", businessId);
        tags.put("userId", userId);
        tags.put("source", source);

        recordHistogram(METRIC_SYNC_TASK_FILES_PROCESSED, count, tags);
        LOG.infof("Processed %d files for business %s, user %s, source %s",
                count, businessId, userId, source);
    }

    /**
     * Utility method to generate a unique operation ID
     */
    private String generateId(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-" +
                Thread.currentThread().getId() + "-" +
                Math.round(Math.random() * 1000);
    }

    /**
     * Creates Micrometer Tag list from a Map
     */
    private List<Tag> createTags(Map<String, String> tags) {
        List<Tag> result = new ArrayList<>();

        if (tags != null && !tags.isEmpty()) {
            tags.forEach((key, value) -> result.add(Tag.of(key, value)));
        }

        return result;
    }

    /**
     * Formats tags as a string for use in metric names
     */
    private String formatTagsForName(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "none";
        }

        StringBuilder sb = new StringBuilder();
        tags.forEach((key, value) -> {
            if (sb.length() > 0) {
                sb.append(".");
            }
            sb.append(key).append("-").append(value);
        });

        return sb.toString();
    }

    /**
     * Operation status for metrics
     */
    public enum OperationStatus {
        SUCCESS,
        FAILURE,
        TIMEOUT,
        ERROR
    }
}
