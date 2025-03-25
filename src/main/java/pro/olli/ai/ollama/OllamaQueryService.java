/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.ai.ollama;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.ai.ChatMessage;
import pro.olli.ai.QueryService;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;

import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Direct implementation of QueryService that calls Ollama API directly.
 * This implementation is lightweight and efficient.
 */
public class OllamaQueryService implements QueryService {

    private static final Logger LOG = Logger.getLogger(OllamaQueryService.class);

    private final io.vertx.mutiny.core.Vertx vertx;
    private final String ollamaEndpoint;
    private final String ollamaModel;

    @Inject
    public OllamaQueryService(io.vertx.mutiny.core.Vertx vertx, String ollamaEndpoint, String ollamaModel) {
        this.vertx = vertx;
        this.ollamaEndpoint = ollamaEndpoint;
        this.ollamaModel = ollamaModel;

        LOG.infof("Created DirectOllamaQueryService with model=%s, endpoint=%s",
            ollamaModel, ollamaEndpoint);
    }

    @Override
    @Timed(type = Timed.OperationType.AI_SERVICE)
    @Retry(maxRetries = 3,
        delay = 2000,
        jitter = 500,
        maxDuration = 30000,
        retryOn = Exception.class,
        abortOn = {AppException.class})
    @Timeout(value = 60, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 30000,
        successThreshold = 2)
    @CircuitBreakerName("ollama-direct-query")
    @Fallback(fallbackMethod = "fallbackQuery")
    public Uni<String> query(String prompt, List<ChatMessage> history) {
        LOG.infof("Querying Ollama with direct API: %s", ollamaModel);

        // Validate input
        if (prompt == null || prompt.trim().isEmpty()) {
            LOG.warn("Empty prompt provided for query");
            return Uni.createFrom().failure(
                new AppException("Empty prompt provided for query",
                    AppException.ErrorType.VALIDATION_ERROR));
        }

        return executeDirectQuery(prompt, history);
    }

    /**
     * Execute a direct query to Ollama API
     */
    private Uni<String> executeDirectQuery(String prompt, List<ChatMessage> history) {
        // Use WebClient to make a non-blocking request
        io.vertx.mutiny.ext.web.client.WebClient webClient = io.vertx.mutiny.ext.web.client.WebClient.create(vertx);

        // Parse URL
        URI uri;
        try {
            uri = new URI(ollamaEndpoint + "/api/generate");
        } catch (Exception e) {
            LOG.errorf("Invalid URL: %s", ollamaEndpoint + "/api/generate", e);
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

        // Build the message array with history
        String systemPrompt = "You are a helpful AI assistant.";
        StringBuilder fullPrompt = new StringBuilder(systemPrompt);

        // Add chat history
        if (history != null && !history.isEmpty()) {
            for (ChatMessage msg : history) {
                String role = msg.role.trim().toLowerCase();
                if ("user".equals(role)) {
                    fullPrompt.append("\n\nUser: ").append(msg.content);
                } else if ("assistant".equals(role)) {
                    fullPrompt.append("\n\nAssistant: ").append(msg.content);
                } else if ("system".equals(role)) {
                    // System messages come first
                    fullPrompt.insert(0, msg.content + "\n\n");
                }
            }
        }

        // Add the current prompt
        fullPrompt.append("\n\nUser: ").append(prompt);
        fullPrompt.append("\n\nAssistant: ");

        // Build request body
        JsonObject requestBody = new JsonObject()
            .put("model", ollamaModel)
            .put("prompt", fullPrompt.toString())
            .put("stream", false);

        LOG.debugf("Sending query with total prompt length: %d", fullPrompt.length());

        return webClient.post(port, host, path)
            .timeout(60000) // 60 second timeout
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(requestBody)
            .map(response -> {
                if (response.statusCode() == 200) {
                    // Parse response from Ollama
                    JsonObject jsonResponse = response.bodyAsJsonObject();
                    String textResponse = jsonResponse.getString("response");

                    if (textResponse == null || textResponse.trim().isEmpty()) {
                        LOG.error("Ollama returned empty response");
                        throw new AppException("Ollama returned empty response",
                            AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                    }

                    LOG.infof("Generated query response with direct API: %s",
                        textResponse.length() > 100 ? textResponse.substring(0, 100) + "..." : textResponse);
                    return textResponse;
                } else {
                    String errorBody = response.bodyAsString();
                    LOG.errorf("Ollama query error: status=%d, body=%s",
                        response.statusCode(), errorBody);
                    throw new AppException("Failed to query Ollama: " + errorBody,
                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
            })
            .onFailure().transform(e -> {
                if (e instanceof AppException) {
                    return e;
                }
                LOG.errorf("Error querying Ollama: %s", e.getMessage());
                return new AppException("Failed to query Ollama", e,
                    AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
            });
    }

    /**
     * Fallback method for when the query fails
     */
    public Uni<String> fallbackQuery(String prompt, List<ChatMessage> history) {
        LOG.warnf("Using fallback query generation for prompt of length: %d", prompt.length());
        return Uni.createFrom().item(() -> {
            String fallbackResponse = "I'm sorry, but I'm currently experiencing technical difficulties. " +
                "This is likely because:\n\n" +
                "1. Ollama is not running or is unreachable at " + ollamaEndpoint + "\n" +
                "2. The required model '" + ollamaModel + "' has not been pulled\n\n" +
                "Please check the server logs for more detailed information.";
            return fallbackResponse;
        });
    }

    /**
     * Health check method
     */
    @Timed(value = "ollama.direct.query.health.check", type = Timed.OperationType.EXTERNAL_SERVICE)
    public Uni<Boolean> healthCheck() {
        LOG.infof("Checking Ollama query service health at endpoint: %s with model: %s", ollamaEndpoint, ollamaModel);

        return query("test", List.of())
            .map(response -> response != null && !response.isEmpty() &&
                !response.contains("I'm sorry, but I'm currently experiencing technical difficulties"))
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Health check failed: %s", e.getMessage());
                return false;
            });
    }
}
