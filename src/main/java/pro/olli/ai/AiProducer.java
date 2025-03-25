/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.ai.ollama.OllamaEmbeddingService;
import pro.olli.ai.ollama.OllamaQueryService;
import pro.olli.ai.vertexai.VertexAiEmbeddingService;
import pro.olli.ai.vertexai.VertexAiQueryService;

@ApplicationScoped
@Startup
public class AiProducer {

    private static final Logger LOG = Logger.getLogger(AiProducer.class);

    @ConfigProperty(name = "gcp.project-id", defaultValue = "olli-ai")
    String projectId;

    @ConfigProperty(name = "gcp.vertex.location", defaultValue = "us-west1")
    String location;

    @ConfigProperty(name = "gcp.vertex.endpoint-id", defaultValue = "your-endpoint-id")
    String endpointId;

    @ConfigProperty(name = "ollama.endpoint", defaultValue = "http://localhost:11434")
    String ollamaEndpoint;

    @ConfigProperty(name = "ollama.embedding.model", defaultValue = "tinyllama")
    String ollamaModel;

    @ConfigProperty(name = "ollama.embedding.dimension", defaultValue = "768")
    int embeddingDimension;

    @ConfigProperty(name = "ai.provider", defaultValue = "vertex")
    String aiProvider;

    @Inject
    io.vertx.mutiny.core.Vertx vertx;

    @Produces
    @ApplicationScoped
    @AiConfiguredService
    public EmbeddingService produceEmbeddingService() {
        LOG.infof("Producing embedding service for provider: %s", aiProvider);

        return switch (aiProvider.toLowerCase()) {
            case "vertex" -> new VertexAiEmbeddingService(projectId, location, endpointId);
            case "ollama" -> new OllamaEmbeddingService(vertx, ollamaEndpoint, ollamaModel, embeddingDimension);
            default -> throw new IllegalArgumentException("Unknown AI provider: " + aiProvider);
        };
    }

    @Produces
    @ApplicationScoped
    @AiConfiguredService
    public QueryService produceQueryService() {
        LOG.infof("Producing query service for provider: %s", aiProvider);

        return switch (aiProvider.toLowerCase()) {
            case "vertex" -> new VertexAiQueryService(projectId, location, endpointId);
            case "ollama" -> {
                // Check if RAG should be enabled (default to false)
                boolean ragEnabled = Boolean.parseBoolean(System.getProperty("ollama.rag.enabled", "false"));
                if (ragEnabled) {
                    LOG.info("Using RAG-enabled Ollama query service");
                    yield null; // Will be injected by CDI
                } else {
                    LOG.info("Using standard Ollama query service");
                    yield new OllamaQueryService(vertx, ollamaEndpoint, ollamaModel);
                }
            }
            default -> throw new IllegalArgumentException("Unknown AI provider: " + aiProvider);
        };
    }
}
