/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.core.resilience;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ResilienceConfiguration {
    private static final Logger LOG = Logger.getLogger(ResilienceConfiguration.class);

    @ConfigProperty(name = "resilience.retry.max-retries", defaultValue = "3")
    int maxRetries;

    @ConfigProperty(name = "resilience.retry.delay-ms", defaultValue = "1000")
    long retryDelayMs;

    @ConfigProperty(name = "resilience.timeout.default-ms", defaultValue = "5000")
    long defaultTimeoutMs;

    @ConfigProperty(name = "resilience.circuit-breaker.request-volume-threshold", defaultValue = "20")
    int requestVolumeThreshold;

    @ConfigProperty(name = "resilience.circuit-breaker.failure-ratio", defaultValue = "0.5")
    double failureRatio;

    @ConfigProperty(name = "resilience.circuit-breaker.delay-ms", defaultValue = "30000")
    long circuitBreakerDelayMs;

    public void initialize(@Observes StartupEvent event) {
        LOG.infof("Initializing resilience configuration with maxRetries=%d, retryDelayMs=%d, " +
                "defaultTimeoutMs=%d, requestVolumeThreshold=%d, failureRatio=%.2f, circuitBreakerDelayMs=%d",
            maxRetries, retryDelayMs, defaultTimeoutMs, requestVolumeThreshold, failureRatio, circuitBreakerDelayMs);
    }

    // Helper methods to access configuration values if needed
}
