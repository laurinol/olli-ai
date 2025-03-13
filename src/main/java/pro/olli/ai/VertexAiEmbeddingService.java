/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai;

import com.google.cloud.aiplatform.v1.EndpointName;
import com.google.cloud.aiplatform.v1.PredictRequest;
import com.google.cloud.aiplatform.v1.PredictResponse;
import com.google.cloud.aiplatform.v1.PredictionServiceClient;
import com.google.cloud.aiplatform.v1.PredictionServiceSettings;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class VertexAiEmbeddingService implements EmbeddingService {

    private static final Logger LOG = Logger.getLogger(VertexAiEmbeddingService.class);

    @ConfigProperty(name = "gcp.project-id", defaultValue = "olli-ai")
    String projectId;

    @ConfigProperty(name = "gcp.vertex.location", defaultValue = "us-west1")
    String location;

    @ConfigProperty(name = "gcp.vertex.endpoint-id", defaultValue = "your-endpoint-id") // Replace with your deployed endpoint ID
    String endpointId;

    protected final PredictionServiceClient predictionServiceClient;

    @Inject
    public VertexAiEmbeddingService() {
        try {
            PredictionServiceSettings settings = PredictionServiceSettings.newBuilder().build();
            this.predictionServiceClient = PredictionServiceClient.create(settings);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize PredictionServiceClient", e);
        }
    }

    @Override
    public Uni<List<Float>> generateEmbedding(String text) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                EndpointName endpointName = EndpointName.of(projectId, location, endpointId);
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
}
