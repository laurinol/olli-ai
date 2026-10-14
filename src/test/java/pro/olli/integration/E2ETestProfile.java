/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.integration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Test profile for E2E tests that ensures the MongoDB repositories are used
 * and all necessary services are properly configured.
 */
public class E2ETestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new HashMap<>();

        // Database configuration
        config.put("database.provider", "mongodb");
        config.put("quarkus.mongodb.connection-string", "mongodb://admin:password@localhost:27017");
        config.put("quarkus.mongodb.database", "olli_test");

        // Task queue configuration
        config.put("queue.provider", "rabbitmq");
        config.put("rabbitmq.host", "localhost");
        config.put("rabbitmq.port", "5672");
        config.put("rabbitmq.queue", "test-sync-queue");
        config.put("rabbitmq.username", "admin");
        config.put("rabbitmq.password", "pass123");

        // AI provider configuration
        config.put("ollama.endpoint", "http://localhost:11434");

        // Enhanced logging for tests
        config.put("quarkus.log.level", "DEBUG");
        config.put("quarkus.log.category.\"pro.olli\"", "DEBUG");

        // Disable auth for testing
        config.put("quarkus.oidc.enabled", "false");

        // Explicitly register MongoDB repositories and test alternatives
        config.put("quarkus.arc.selected-alternatives",
            "pro.olli.repository.mongodb.MongoUserRepository," +
            "pro.olli.repository.mongodb.MongoChatRepository," +
            "pro.olli.repository.mongodb.MongoSyncRepository," +
            "pro.olli.repository.mongodb.MongoStatsRepository," +
            "pro.olli.repository.mongodb.MongoBillingRepository," +
            "pro.olli.worker.MockIndexServiceClientFactory," +
            "pro.olli.worker.MockEmbeddingService");

        return config;
    }

    @Override
    public java.util.Set<Class<?>> getEnabledAlternatives() {
        return Set.of();
    }

    // Comment this out for now
    // public java.util.Set<java.lang.Class<?>> getExtraClassesForDiscovery() {
    //     return java.util.Set.of(
    //         E2ETestResource.class,
    //         SimpleTestResource.class
    //     );
    // }

    @Override
    public String getConfigProfile() {
        return "test";
    }

    @Override
    public boolean disableApplicationLifecycleObservers() {
        return false;
    }

    // We'll register the E2ETestResource class in a different way
}
