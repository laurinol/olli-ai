/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.ai.ollama;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.ai.EmbeddingService;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;

/**
 * Direct implementation of EmbeddingService that calls Ollama API directly. This implementation is lightweight and
 * efficient.
 */
public class OllamaEmbeddingService implements EmbeddingService {

    private static final Logger LOG = Logger.getLogger(OllamaEmbeddingService.class);

    private final io.vertx.mutiny.core.Vertx vertx;
    private final String ollamaEndpoint;
    private final String ollamaModel;
    private final int embeddingDimension;

    public OllamaEmbeddingService(io.vertx.mutiny.core.Vertx vertx, String ollamaEndpoint, String ollamaModel,
        int embeddingDimension) {
        this.vertx = vertx;
        this.ollamaEndpoint = ollamaEndpoint;
        this.ollamaModel = ollamaModel;
        this.embeddingDimension = embeddingDimension;

        LOG.infof("Created OllamaEmbeddingService with model=%s, dimension=%d, endpoint=%s",
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
    @Timeout(value = 30, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 15000,
        successThreshold = 2)
    @CircuitBreakerName("ollama-direct-embedding")
    @Fallback(fallbackMethod = "fallbackEmbedding")
    public Uni<List<Float>> generateEmbedding(String text) {
        LOG.infof("Generating embedding with direct Ollama API: %s", ollamaModel);

        if (text == null || text.trim().isEmpty()) {
            LOG.warn("Empty text provided for embedding generation");
            return Uni.createFrom().failure(
                new AppException("Empty text provided for embedding generation",
                    AppException.ErrorType.VALIDATION_ERROR));
        }

        // Use WebClient to make a non-blocking request
        io.vertx.mutiny.ext.web.client.WebClient webClient = io.vertx.mutiny.ext.web.client.WebClient.create(vertx);

        // Parse URL
        URI uri;
        try {
            uri = new URI(ollamaEndpoint + "/api/embeddings");
        } catch (Exception e) {
            LOG.errorf("Invalid URL: %s", ollamaEndpoint + "/api/embeddings", e);
            return Uni.createFrom().failure(
                new AppException("Invalid Ollama endpoint URL", e,
                    AppException.ErrorType.CONFIGURATION_ERROR));
        }

        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(uri.getScheme()) ? 443 : 80;
        }
        String path = uri.getPath();

        // Build request body
        JsonObject requestBody = new JsonObject()
            .put("model", ollamaModel)
            .put("prompt", text);

        LOG.debugf("Sending embedding request for text length: %d", text.length());

        return webClient.post(port, host, path)
            .timeout(30000) // 30 second timeout
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(requestBody)
            .map(response -> {
                if (response.statusCode() == 200) {
                    // Parse embedding from response
                    JsonObject jsonResponse = response.bodyAsJsonObject();

                    // Access the embedding vector
                    JsonArray embeddingArray = jsonResponse.getJsonArray("embedding");
                    if (embeddingArray == null || embeddingArray.isEmpty()) {
                        LOG.error("Ollama returned empty embedding array");
                        throw new AppException("Ollama returned empty embedding array",
                            AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                    }

                    // Convert JsonArray to List<Float>
                    List<Float> embedding = new ArrayList<>(embeddingArray.size());
                    for (int i = 0; i < embeddingArray.size(); i++) {
                        embedding.add(embeddingArray.getFloat(i));
                    }

                    LOG.infof("Generated embedding with direct API, length: %d", embedding.size());
                    return embedding;
                } else {
                    String errorBody = response.bodyAsString();
                    LOG.errorf("Ollama embedding error: status=%d, body=%s",
                        response.statusCode(), errorBody);
                    throw new AppException("Failed to generate embedding with Ollama: " + errorBody,
                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
            })
            .onFailure().transform(e -> {
                if (e instanceof AppException) {
                    return e;
                }
                LOG.errorf("Error generating embedding: %s", e.getMessage());
                return new AppException("Failed to generate embedding with Ollama", e,
                    AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
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
    @Timed(value = "ollama.direct.health.check", type = Timed.OperationType.EXTERNAL_SERVICE)
    public Uni<Boolean> healthCheck() {
        LOG.infof("Checking Ollama health at endpoint: %s with model: %s", ollamaEndpoint, ollamaModel);

        return generateEmbedding("test")
            .map(embedding -> embedding != null && !embedding.isEmpty())
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Health check failed: %s", e.getMessage());
                return false;
            });
    }
}
