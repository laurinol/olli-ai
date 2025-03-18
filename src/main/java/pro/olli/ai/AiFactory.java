/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import pro.olli.ai.ollama.OllamaEmbeddingService;
import pro.olli.ai.ollama.OllamaQueryService;
import pro.olli.ai.vertexai.VertexAiEmbeddingService;
import pro.olli.ai.vertexai.VertexAiQueryService;

@ApplicationScoped
@Startup
public class AiFactory {

    @ConfigProperty(name = "ai.provider", defaultValue = "vertex")
    String aiProvider;

    @Produces
    @ApplicationScoped
    public EmbeddingService createEmbeddingService() {
        return switch (aiProvider.toLowerCase()) {
            case "vertex" -> new VertexAiEmbeddingService();
            case "ollama" -> new OllamaEmbeddingService();
            default -> throw new IllegalArgumentException("Unknown AI provider: " + aiProvider);
        };
    }

    @Produces
    @ApplicationScoped
    public QueryService createQueryService() {
        return switch (aiProvider.toLowerCase()) {
            case "vertex" -> new VertexAiQueryService();
            case "ollama" -> new OllamaQueryService();
            default -> throw new IllegalArgumentException("Unknown AI provider: " + aiProvider);
        };
    }
}
