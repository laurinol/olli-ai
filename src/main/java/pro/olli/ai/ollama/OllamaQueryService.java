/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai.ollama;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import pro.olli.ai.ChatMessage;
import pro.olli.ai.QueryService;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;

public class OllamaQueryService extends OllamaEmbeddingService implements QueryService {

    private static final Logger LOG = Logger.getLogger(OllamaQueryService.class);

    private final OllamaApi ollamaApi;

    public OllamaQueryService() {
        this.ollamaApi = QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(ollamaEndpoint))
            .build(OllamaApi.class);
    }

    @Override
    @Timed(type = Timed.OperationType.AI_SERVICE)
    @Retry(maxRetries = 3,
        delay = 1000,
        jitter = 200,
        maxDuration = 10000,
        retryOn = Exception.class,
        abortOn = {AppException.class})
    @Timeout(value = 8, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 15000,
        successThreshold = 2)
    @CircuitBreakerName("ollama-query")
    @Fallback(fallbackMethod = "fallbackQuery")
    public Uni<String> query(String prompt, List<ChatMessage> history) {
        return ollamaApi.chat(new ChatRequest("llama3", prompt, history))
            .map(response -> {
                if (response.message == null || response.message.content == null) {
                    throw new AppException("Ollama returned empty chat response",
                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
                LOG.infof("Generated chat response with Ollama: %s", response.message.content);
                return response.message.content;
            })
            .onFailure().transform(e -> {
                LOG.errorf("Chat query failed: %s", e.getMessage());
                if (e instanceof AppException) {
                    return e;
                }
                return new AppException("Failed to generate chat response with Ollama", e,
                    AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
            });
    }

    public Uni<String> fallbackQuery(String prompt, List<ChatMessage> history) {
        LOG.warnf("Using fallback query generation for prompt of length: %d", prompt.length());
        return Uni.createFrom().item(() -> {
            String fallbackResponse = "I'm sorry, but I'm currently experiencing technical difficulties. " +
                "Please try again in a few moments or contact support if the issue persists.";
            return fallbackResponse;
        });
    }

    @Path("/api")
    public interface OllamaApi {
        @POST
        @Path("/chat")
        @Produces(MediaType.APPLICATION_JSON)
        @Consumes(MediaType.APPLICATION_JSON)
        Uni<ChatResponse> chat(ChatRequest request);
    }

    static class ChatRequest {
        public String model;
        public String prompt;
        public List<ChatMessage> messages;

        public ChatRequest(String model, String prompt, List<ChatMessage> history) {
            this.model = model;
            this.prompt = prompt;
            this.messages = history;
        }
    }

    static class ChatResponse {
        public ChatMessage message;
    }
}
