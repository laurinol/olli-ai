/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.core.metrics;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors circuit breaker state changes and records metrics.
 */
@ApplicationScoped
public class CircuitBreakerMetrics {

    private static final Logger LOG = Logger.getLogger(CircuitBreakerMetrics.class);
    
    private final CircuitBreakerMaintenance circuitBreakerMaintenance;
    private final PerformanceMetrics metrics;
    private final Map<String, CircuitBreakerState> circuitBreakerStates = new ConcurrentHashMap<>();
    
    @Inject
    public CircuitBreakerMetrics(
            CircuitBreakerMaintenance circuitBreakerMaintenance,
            PerformanceMetrics metrics) {
        this.circuitBreakerMaintenance = circuitBreakerMaintenance;
        this.metrics = metrics;
    }
    
    /**
     * Initialize by checking all circuit breakers
     */
    void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing circuit breaker monitoring");
        try {
            // Use reflection since the API might change between versions
            java.lang.reflect.Method getNamesMethod = 
                circuitBreakerMaintenance.getClass().getMethod("getCircuitBreakerNames");
            @SuppressWarnings("unchecked")
            java.util.Set<String> circuitNames = (java.util.Set<String>) getNamesMethod.invoke(circuitBreakerMaintenance);
                
            java.lang.reflect.Method getStateMethod = 
                circuitBreakerMaintenance.getClass().getMethod("getState", String.class);
            
            for (String name : circuitNames) {
                Object state = getStateMethod.invoke(circuitBreakerMaintenance, name);
                CircuitBreakerState stateEnum = CircuitBreakerState.valueOf(state.toString());
                circuitBreakerStates.put(name, stateEnum);
                
                LOG.infof("Circuit breaker %s initial state: %s", name, stateEnum);
                recordState(name, stateEnum);
            }
        } catch (Exception e) {
            LOG.warnf("Error initializing circuit breaker metrics: %s", e.getMessage());
        }
    }
    
    /**
     * Periodically check circuit breaker states
     * This can be triggered by a scheduled task since we don't have direct event access
     */
    public void checkCircuitBreakerStates() {
        try {
            // Use reflection since the API might change between versions
            java.lang.reflect.Method getNamesMethod = 
                circuitBreakerMaintenance.getClass().getMethod("getCircuitBreakerNames");
            @SuppressWarnings("unchecked")
            java.util.Set<String> circuitNames = (java.util.Set<String>) getNamesMethod.invoke(circuitBreakerMaintenance);
                
            java.lang.reflect.Method getStateMethod = 
                circuitBreakerMaintenance.getClass().getMethod("getState", String.class);
            
            for (String name : circuitNames) {
                Object stateObj = getStateMethod.invoke(circuitBreakerMaintenance, name);
                CircuitBreakerState newState = CircuitBreakerState.valueOf(stateObj.toString());
                CircuitBreakerState oldState = circuitBreakerStates.put(name, newState);
                
                if (oldState != newState) {
                    LOG.infof("Circuit breaker %s state changed: %s -> %s", name, oldState, newState);
                    // Record the state change
                    recordState(name, newState);
                }
            }
        } catch (Exception e) {
            LOG.warnf("Error checking circuit breaker states: %s", e.getMessage());
        }
    }
    
    /**
     * Record the state in metrics
     */
    private void recordState(String name, CircuitBreakerState state) {
        Map<String, String> tags = new HashMap<>();
        tags.put("name", name);
        
        boolean isOpen = state == CircuitBreakerState.OPEN;
        metrics.recordCircuitBreakerState(name, isOpen, tags);
        
        // Also increment a counter for state transitions
        metrics.incrementCounter("circuit.breaker.state." + state.toString().toLowerCase(), tags);
    }
}
