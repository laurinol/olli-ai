/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

import io.quarkus.runtime.Startup;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import pro.olli.ai.ollama.EnhancedRAGOllamaQueryService;
import pro.olli.ai.ollama.RAGOllamaQueryService;
import pro.olli.ai.onnx.OnnxEmbeddingService;
import pro.olli.ai.tokenizer.BytePairEncodingTokenizer;
import pro.olli.ai.vertexai.VertexAiEmbeddingService;
import pro.olli.ai.vertexai.VertexAiQueryService;
import pro.olli.config.AppConfig;
import pro.olli.repository.SyncRepository;

@ApplicationScoped
@Startup
public class AiProducer {

    private static final Logger LOG = Logger.getLogger(AiProducer.class);

    private final AppConfig config;
    private final Vertx vertx;
    private final SyncRepository syncRepository;

    @Inject
    public AiProducer(AppConfig config, Vertx vertx, SyncRepository syncRepository) {
        this.config = config;
        this.vertx = vertx;
        this.syncRepository = syncRepository;
        LOG.info("Creating AIProducer which will produce query and embedding objects");
    }

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
    //@AiConfiguredService
    public EmbeddingService produceEmbeddingService(AppConfig config) {
        // In production, always use VertexAI
        if (!config.isDevMode()) {
            LOG.info("Production environment detected - using VertexAI for embeddings");
            return new VertexAiEmbeddingService(config);
        } else {
            // Default to ONNX embeddings for development
            LOG.info("Development environment using local ONNX embeddings");
            return new OnnxEmbeddingService(config);
        }
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
        // Production always uses VertexAI
        if (!config.isDevMode()) {
            LOG.info("Production environment - using VertexAI for queries");
            return new VertexAiQueryService(config);
        } else {
            // Check implementation type from properties
            String implType = System.getProperty("ollama.implementation", "direct");

            //if ("rag".equalsIgnoreCase(implType)) {
                // RAG implementation requested
                String ragImpl = System.getProperty("ollama.rag.implementation", "simple");

                LOG.info("Development using RAG Ollama implementation (" + ragImpl + ")");

                // Use the injected SyncRepository (will be memory repository when using RAG)
                LOG.info("Using injected SyncRepository for RAG: " + syncRepository.getClass().getName());

                //if ("enhanced".equalsIgnoreCase(ragImpl)) {
                    // Create a new tokenizer instance directly
                    BytePairEncodingTokenizer tokenizer = new BytePairEncodingTokenizer(config);
                    tokenizer.initialize(); // Manually initialize
                    LOG.info("Using EnhancedRAGOllamaQueryService with manually initialized tokenizer");
                    return new EnhancedRAGOllamaQueryService(config, vertx, produceEmbeddingService(config), syncRepository, tokenizer);
                /*} else {
                    LOG.info("Using RAGOllamaQueryService");
                    return new RAGOllamaQueryService(config, vertx, produceEmbeddingService(config), syncRepository);
                }*/
            /*} else {
                // Standard implementation
                boolean useDirectImpl = Boolean.parseBoolean(System.getProperty("ollama.use.direct", "true"));

                LOG.info("Development using " + (useDirectImpl ? "direct" : "standard") + " Ollama implementation");
                return useDirectImpl
                    ? new DirectOllamaQueryService(config, vertx)
                    : new OllamaQueryService(vertx, config.getOllamaEndpoint(), config.getOllamaChatModel());
            }*/
        }
    }

}
