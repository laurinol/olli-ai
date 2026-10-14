/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.integration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Test profile for embedding and query tests that uses the appropriate services
 * and repositories for AI functionality.
 */
public class EmbeddingTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new HashMap<>();

        // Database configuration
        config.put("database.provider", "mongodb");
        config.put("quarkus.mongodb.connection-string", "mongodb://admin:password@localhost:27017");
        config.put("quarkus.mongodb.database", "olli_test");

        // AI provider configuration - use Ollama for embeddings and queries
        config.put("ollama.endpoint", "http://localhost:11434");
        config.put("ollama.embedding-model", "nomic-embed-text");
        config.put("ollama.query-model", "tinyllama");

        // Enhanced logging for tests
        config.put("quarkus.log.level", "DEBUG");
        config.put("quarkus.log.category.\"pro.olli\"", "DEBUG");

        // Disable auth for testing
        config.put("quarkus.oidc.enabled", "false");

        // Explicitly register MongoDB repositories
        config.put("quarkus.arc.selected-alternatives",
            "pro.olli.repository.mongodb.MongoUserRepository," +
            "pro.olli.repository.mongodb.MongoChatRepository," +
            "pro.olli.repository.mongodb.MongoSyncRepository," +
            "pro.olli.repository.mongodb.MongoStatsRepository," +
            "pro.olli.repository.mongodb.MongoBillingRepository");

        return config;
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of();
    }

    @Override
    public String getConfigProfile() {
        return "test";
    }

    @Override
    public boolean disableApplicationLifecycleObservers() {
        return false;
    }
}
