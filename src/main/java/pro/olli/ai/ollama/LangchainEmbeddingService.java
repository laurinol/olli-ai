/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai.ollama;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.ai.EmbeddingService;
import pro.olli.ai.ollama.OllamaImplementation;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Implementation of EmbeddingService using the langchain4j library
 */
@ApplicationScoped
@OllamaImplementation(OllamaImplementation.ImplementationType.LANGCHAIN)
@LangchainService
public class LangchainEmbeddingService implements EmbeddingService {

    private static final Logger LOG = Logger.getLogger(LangchainEmbeddingService.class);

    private final OllamaEmbeddingModel embeddingModel;
    private final String ollamaModel;
    private final int embeddingDimension;
    private final String ollamaEndpoint;

    @Inject
    public LangchainEmbeddingService(
            OllamaEmbeddingModel embeddingModel,
            @ConfigProperty(name = "ollama.endpoint", defaultValue = "http://localhost:11434") String ollamaEndpoint,
            @ConfigProperty(name = "ollama.embedding.model", defaultValue = "tinyllama") String ollamaModel,
            @ConfigProperty(name = "ollama.embedding.dimension", defaultValue = "768") int embeddingDimension) {
        this.embeddingModel = embeddingModel;
        this.ollamaModel = ollamaModel;
        this.embeddingDimension = embeddingDimension;
        this.ollamaEndpoint = ollamaEndpoint;
        
        LOG.infof("Created LangchainEmbeddingService with model=%s, dimension=%d, endpoint=%s", 
            ollamaModel, embeddingDimension, ollamaEndpoint);
    }

    @Override
    @Timed(type = Timed.OperationType.AI_SERVICE)
    @Retry(maxRetries = 3,
        delay = 1000,
        jitter = 200,
        maxDuration = 10000,
        retryOn = Exception.class,
        abortOn = {AppException.class})
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 15000,
        successThreshold = 2)
    @CircuitBreakerName("ollama-langchain-embedding")
    @Fallback(fallbackMethod = "fallbackEmbedding")
    public Uni<List<Float>> generateEmbedding(String text) {
        LOG.infof("Generating embedding with langchain4j model: %s", ollamaModel);
        
        return Uni.createFrom().emitter(emitter -> {
            try {
                Embedding embedding = embeddingModel.embed(text).content();
                List<Float> vectorList = embedding.vectorAsList();
                
                if (vectorList == null || vectorList.isEmpty()) {
                    throw new AppException("Ollama returned empty embedding",
                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
                
                LOG.infof("Generated embedding with langchain4j, length: %d", vectorList.size());
                emitter.complete(vectorList);
            } catch (Exception e) {
                LOG.errorf("Failed to generate embedding: %s", e.getMessage());
                emitter.fail(new AppException("Failed to generate embedding", e,
                    AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
            }
        });
    }

    public Uni<List<Float>> fallbackEmbedding(String text) {
        LOG.warnf("Using fallback embedding generation for text of length: %d", text.length());
        return Uni.createFrom().item(() -> generateFallbackEmbedding(text));
    }

    private List<Float> generateFallbackEmbedding(String text) {
        // Create a deterministic embedding based on text hash
        int seed = text.hashCode();
        Random random = new Random(seed);

        // Generate a simplified embedding vector with the configured dimension
        List<Float> embedding = new ArrayList<>(embeddingDimension);
        for (int i = 0; i < embeddingDimension; i++) {
            embedding.add((random.nextFloat() * 2) - 1); // Value between -1 and 1
        }

        // Normalize the vector
        float sum = 0;
        for (float v : embedding) {
            sum += v * v;
        }
        float norm = (float) Math.sqrt(sum);

        for (int i = 0; i < embeddingDimension; i++) {
            embedding.set(i, embedding.get(i) / norm);
        }

        LOG.infof("Generated fallback embedding, length: %d", embedding.size());
        return embedding;
    }

    // For testing the health of the Ollama service
    @Timed(value = "ollama.health.check", type = Timed.OperationType.EXTERNAL_SERVICE)
    public Uni<Boolean> healthCheck() {
        LOG.infof("Checking Ollama health at endpoint: %s with model: %s", ollamaEndpoint, ollamaModel);
        
        return Uni.createFrom().emitter(emitter -> {
            try {
                // Try to generate a simple embedding as a health check
                Embedding embedding = embeddingModel.embed("test").content();
                boolean success = embedding != null && !embedding.vectorAsList().isEmpty();
                LOG.infof("Successfully connected to Ollama at: %s, health check %s", 
                    ollamaEndpoint, success ? "passed" : "failed");
                emitter.complete(success);
            } catch (Exception e) {
                LOG.errorf("Failed to connect to Ollama at %s: %s", ollamaEndpoint, e.getMessage());
                emitter.complete(false);
            }
        });
    }
}