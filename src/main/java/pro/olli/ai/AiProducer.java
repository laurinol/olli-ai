/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.ai.ollama.DirectOllamaQueryService;
import pro.olli.ai.ollama.EnhancedRAGOllamaQueryService;
import pro.olli.ai.ollama.OllamaQueryService;
import pro.olli.ai.ollama.SimpleRAGOllamaQueryService;
import pro.olli.ai.onnx.OnnxEmbeddingService;
import pro.olli.ai.vertexai.VertexAiEmbeddingService;
import pro.olli.ai.vertexai.VertexAiQueryService;
import pro.olli.repository.SyncRepository;

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

    @ConfigProperty(name = "onnx.enabled", defaultValue = "false")
    boolean onnxEnabled;

    @Inject
    io.vertx.mutiny.core.Vertx vertx;

    @Inject
    @Named("mongodb-sync")
    SyncRepository syncRepository;

    /**
     * Produces the appropriate embedding service based on environment and configuration.
     * There are two main embedding implementation types:
     * 1. VertexAI Embeddings - Used in production environments (through GCP)
     * 2. ONNX Embeddings - Local embeddings using on-device models
     *
     * Ollama itself doesn't have good embedding capabilities, so we always use ONNX in dev,
     * unless specifically configured to use VertexAI.
     */
    @Produces
    @ApplicationScoped
    @AiConfiguredService
    public EmbeddingService produceEmbeddingService() {
        // Check if we're in production profile
        String profile = System.getProperty("quarkus.profile", "dev");
        boolean isProd = "prod".equals(profile);

        // In production, always use VertexAI
        if (isProd) {
            LOG.info("Production environment detected - using VertexAI for embeddings");
            return new VertexAiEmbeddingService(projectId, location, endpointId);
        }

        // For development, default to local ONNX embeddings, but allow override to VertexAI if configured
        if ("vertex".equals(aiProvider.toLowerCase())) {
            LOG.info("Development environment using VertexAI embeddings (explicitly configured)");
            return new VertexAiEmbeddingService(projectId, location, endpointId);
        }

        // Default to ONNX embeddings for development
        LOG.info("Development environment using local ONNX embeddings");
        return new OnnxEmbeddingService();
    }


    /**
     * Produces the query service based on environment and configuration.
     * 
     * - Production: Always uses VertexAI (Google Cloud)
     * - Development: Uses Ollama with direct API implementation by default
     * 
     * Note: The multiple implementations for Ollama are only for local development
     * to simulate the production environment without requiring GCP resources.
     */
    @Produces
    @ApplicationScoped
    @AiConfiguredService
    public QueryService produceQueryService() {
        // Check if we're in production profile
        String profile = System.getProperty("quarkus.profile", "dev");
        boolean isProd = "prod".equals(profile);
        
        // Production always uses VertexAI
        if (isProd) {
            LOG.info("Production environment - using VertexAI for queries");
            return new VertexAiQueryService(projectId, location, endpointId);
        }
        
        // For explicit VertexAI in development
        if ("vertex".equals(aiProvider.toLowerCase())) {
            LOG.info("Development using VertexAI queries (explicitly configured)");
            return new VertexAiQueryService(projectId, location, endpointId);
        }
        
        // For development Ollama, use direct implementation by default
        boolean useDirectImpl = Boolean.parseBoolean(System.getProperty("ollama.use.direct", "true"));
        
        LOG.info("Development using " + (useDirectImpl ? "direct" : "standard") + " Ollama implementation");
        return useDirectImpl 
            ? new DirectOllamaQueryService(vertx, ollamaEndpoint, ollamaModel)
            : new OllamaQueryService(vertx, ollamaEndpoint, ollamaModel);
    }

}
