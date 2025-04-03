/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai.vertexai;

import com.google.cloud.aiplatform.v1.EndpointName;
import com.google.cloud.aiplatform.v1.PredictRequest;
import com.google.cloud.aiplatform.v1.PredictResponse;
import com.google.cloud.aiplatform.v1.PredictionServiceClient;
import com.google.cloud.aiplatform.v1.PredictionServiceSettings;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.Random;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import pro.olli.ai.EmbeddingService;
import pro.olli.config.AppConfig;
import pro.olli.core.metrics.Timed;

import jakarta.enterprise.context.ApplicationScoped;
import pro.olli.ai.AiConfiguredService;
import pro.olli.ai.AiImplementationType;

public class VertexAiEmbeddingService implements EmbeddingService {

    private static final Logger LOG = Logger.getLogger(VertexAiEmbeddingService.class);

    private final AppConfig config;
    protected final PredictionServiceClient predictionServiceClient;

    @Inject
    public VertexAiEmbeddingService(AppConfig config) {
        LOG.info("Creating VertexAI Embedding Service");
        this.config = config;

        try {
            PredictionServiceSettings settings = PredictionServiceSettings.newBuilder().build();
            this.predictionServiceClient = PredictionServiceClient.create(settings);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize PredictionServiceClient", e);
        }
    }

    @Override
    @Timed(type = Timed.OperationType.AI_SERVICE)
    @Retry(maxRetries = 3,
        delay = 1000,
        jitter = 200,
        maxDuration = 10000)
    @Timeout(5000)
    @CircuitBreaker(requestVolumeThreshold = 15,
        failureRatio = 0.4,
        delay = 20000,
        successThreshold = 2)
    @CircuitBreakerName("vertex-ai")
    @Fallback(fallbackMethod = "fallbackEmbedding")
    public Uni<List<Float>> generateEmbedding(String text) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                EndpointName endpointName = EndpointName.of(config.getGcpProjectId(), config.getGcpVertexLocation(),
                    config.getGcpVertexEndpoint());
                PredictRequest.Builder requestBuilder = PredictRequest.newBuilder()
                    .setEndpoint(endpointName.toString())
                    .addInstances(buildInstance(text));

                PredictResponse response = predictionServiceClient.predict(requestBuilder.build());
                List<Float> embedding = extractEmbedding(response);

                LOG.infof("Generated embedding for text with Vertex AI, length: %d", embedding.size());
                emitter.complete(embedding);
            } catch (Exception e) {
                LOG.errorf("Failed to generate embedding with Vertex AI: %s", e.getMessage());
                emitter.fail(e);
            }
        });
    }

    // Fallback method that provides a basic embedding when the primary method fails
    public Uni<List<Float>> fallbackEmbedding(String text) {
        // Generate a simple fallback embedding (could be based on hashing or simplified model)
        // This ensures the system can continue to function when Vertex AI is unavailable
        return Uni.createFrom().item(() -> generateSimpleFallbackEmbedding(text));
    }

    private Value buildInstance(String text) throws IOException {
        // Structure matches Vertex AI embedding input: {"content": "your text"}
        String json = String.format("{\"content\": \"%s\"}", text);
        Value.Builder valueBuilder = Value.newBuilder();
        JsonFormat.parser().merge(json, valueBuilder);
        return valueBuilder.build();
    }

    private List<Float> extractEmbedding(PredictResponse response) {
        // Extract embedding from predictions[0]
        Value prediction = response.getPredictionsList().get(0);
        try {
            String json = JsonFormat.printer().print(prediction);
            Value.Builder valueBuilder = Value.newBuilder();
            JsonFormat.parser().ignoringUnknownFields().merge(json, valueBuilder);
            Value parsedValue = valueBuilder.build();

            return parsedValue.getStructValue()
                .getFieldsOrThrow("embeddings")
                .getStructValue()
                .getFieldsOrThrow("values")
                .getListValue()
                .getValuesList()
                .stream()
                .map(v -> (float) v.getNumberValue())
                .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.errorf("Failed to parse embedding response: %s", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Float> generateSimpleFallbackEmbedding(String text) {
        // A very simple embedding generator based on text hashing
        // TODO: Real implementation would use a more sophisticated approach
        int seed = text.hashCode();
        Random random = new Random(seed);

        // Generate a simplified embedding vector with the expected dimension
        int embeddingDimension = 768; // Match your model's dimension
        float[] embedding = new float[embeddingDimension];
        for (int i = 0; i < embeddingDimension; i++) {
            embedding[i] = (random.nextFloat() * 2) - 1; // Value between -1 and 1
        }

        // Normalize the vector
        float sum = 0;
        for (float v : embedding) sum += v * v;
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < embeddingDimension; i++) {
            embedding[i] /= norm;
        }

        // Convert to List<Float>
        List<Float> result = new java.util.ArrayList<>(embeddingDimension);
        for (float v : embedding) result.add(v);

        return result;
    }
}
