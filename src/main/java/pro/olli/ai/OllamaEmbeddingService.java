/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.runtime.Startup;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import pro.olli.core.resilience.exception.AppException;

public class OllamaEmbeddingService implements EmbeddingService {

    private static final Logger LOG = Logger.getLogger(OllamaEmbeddingService.class);

    @ConfigProperty(name = "ollama.endpoint", defaultValue = "http://localhost:11434")
    protected String ollamaEndpoint;

    @ConfigProperty(name = "ollama.embedding.model", defaultValue = "llama3")
    String ollamaModel;

    @ConfigProperty(name = "ollama.embedding.dimension", defaultValue = "768")
    int embeddingDimension;

    private final OllamaApi ollamaApi;

    public OllamaEmbeddingService() {
        this.ollamaApi = QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(ollamaEndpoint))
            .build(OllamaApi.class);
    }

    @Override
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
    @CircuitBreakerName("ollama-embedding")
    @Fallback(fallbackMethod = "fallbackEmbedding")
    public Uni<List<Float>> generateEmbedding(String text) {
        LOG.infof("Generating embedding with Ollama model: %s", ollamaModel);
        return ollamaApi.embeddings(new EmbeddingRequest(ollamaModel, text))
            .map(response -> {
                if (response.embedding == null || response.embedding.isEmpty()) {
                    throw new AppException("Ollama returned empty embedding",
                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
                LOG.infof("Generated embedding with Ollama, length: %d", response.embedding.size());
                return response.embedding;
            })
            .onFailure().transform(e -> {
                LOG.errorf("Failed to generate embedding with Ollama: %s", e.getMessage());
                if (e instanceof AppException) {
                    return e;
                }
                return new AppException("Failed to generate embedding with Ollama", e,
                    AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
            });    }


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

    @Path("/api")
    public interface OllamaApi {
        @POST
        @Path("/embeddings")
        @Produces(MediaType.APPLICATION_JSON)
        @Consumes(MediaType.APPLICATION_JSON)
        Uni<EmbeddingResponse> embeddings(EmbeddingRequest request); // No RestBody needed
    }

    public static class EmbeddingRequest {
        public String model;
        public String prompt;

        public EmbeddingRequest(String model, String prompt) {
            this.model = model;
            this.prompt = prompt;
        }
    }

    public static class EmbeddingResponse {
        public List<Float> embedding;
    }

    // For testing the health of the Ollama service
    public Uni<Boolean> healthCheck() {
        return ollamaApi.embeddings(new EmbeddingRequest(ollamaModel, "test"))
            .map(response -> response != null && response.embedding != null && !response.embedding.isEmpty())
            .onFailure().recoverWithItem(false);
    }
}
