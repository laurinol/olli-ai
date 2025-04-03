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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import java.time.temporal.ChronoUnit;
import jakarta.inject.Inject;
import pro.olli.ai.ChatMessage;
import pro.olli.ai.QueryService;
import pro.olli.config.AppConfig;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;

import java.io.IOException;
import java.util.List;

public class VertexAiQueryService implements QueryService {

    private static final Logger LOG = Logger.getLogger(VertexAiQueryService.class);

    private final AppConfig config;
    protected final PredictionServiceClient predictionServiceClient;

    @ConfigProperty(name = "gcp.vertex.chat-model", defaultValue = "gemini-1.5-pro")
    String chatModel;

    @Inject
    public VertexAiQueryService(AppConfig config) {
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
        maxDuration = 10000,
        retryOn = Exception.class,
        abortOn = {AppException.class})
    @Timeout(value = 10, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 15,
        failureRatio = 0.4,
        delay = 20000,
        successThreshold = 2)
    @CircuitBreakerName("vertex-ai-query")
    @Fallback(fallbackMethod = "fallbackQuery")
    public Uni<String> query(String prompt, List<ChatMessage> history) {
        return Uni.createFrom().emitter(emitter -> {
            try {
                EndpointName endpointName = EndpointName.of(config.getGcpProjectId(), config.getGcpVertexLocation(),
                    config.getGcpVertexEndpoint());
                PredictRequest.Builder requestBuilder = PredictRequest.newBuilder()
                    .setEndpoint(endpointName.toString())
                    .addInstances(buildChatInstance(prompt, history));

                PredictResponse response = predictionServiceClient.predict(requestBuilder.build());
                String result = extractChatResponse(response);
                if (result == null || result.isEmpty()) {
                    throw new AppException("Vertex AI returned empty chat response",
                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
                LOG.infof("Generated chat response with Vertex AI: %s", result);
                emitter.complete(result);
            } catch (Exception e) {
                LOG.errorf("Chat query failed: %s", e.getMessage());
                if (e instanceof AppException) {
                    emitter.fail(e);
                } else {
                    emitter.fail(new AppException("Failed to generate chat response with Vertex AI", e,
                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
                }
            }
        });
    }

    private Value buildChatInstance(String prompt, List<ChatMessage> history) throws IOException {
        StringBuilder json = new StringBuilder("{\"contents\": [");
        for (int i = 0; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            json.append(String.format("{\"role\": \"%s\", \"parts\": [{\"text\": \"%s\"}]}", msg.role, msg.content));
            if (i < history.size() - 1) json.append(",");
        }
        json.append(String.format(",{\"role\": \"user\", \"parts\": [{\"text\": \"%s\"}]}]}", prompt));
        Value.Builder valueBuilder = Value.newBuilder();
        JsonFormat.parser().merge(json.toString(), valueBuilder);
        return valueBuilder.build();
    }

    private String extractChatResponse(PredictResponse response) {
        Value prediction = response.getPredictionsList().get(0);
        try {
            String json = JsonFormat.printer().print(prediction);
            Value.Builder valueBuilder = Value.newBuilder();
            JsonFormat.parser().ignoringUnknownFields().merge(json, valueBuilder);
            Value parsedValue = valueBuilder.build();
            return parsedValue.getStructValue()
                .getFieldsOrThrow("candidates")
                .getListValue()
                .getValuesList()
                .get(0)
                .getStructValue()
                .getFieldsOrThrow("content")
                .getStructValue()
                .getFieldsOrThrow("parts")
                .getListValue()
                .getValuesList()
                .get(0)
                .getStructValue()
                .getFieldsOrThrow("text")
                .getStringValue();
        } catch (Exception e) {
            LOG.errorf("Failed to parse chat response: %s", e.getMessage());
            throw new AppException("Failed to parse Vertex AI chat response", e,
                AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
        }
    }

    public Uni<String> fallbackQuery(String prompt, List<ChatMessage> history) {
        LOG.warnf("Using fallback query generation for prompt of length: %d", prompt.length());
        return Uni.createFrom().item(() -> {
            String fallbackResponse = "I'm sorry, but I'm currently experiencing technical difficulties. " +
                "Please try again in a few moments or contact support if the issue persists.";
            return fallbackResponse;
        });
    }
}
