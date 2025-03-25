/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai.ollama;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
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
import pro.olli.ai.ChatMessage;
import pro.olli.ai.QueryService;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;

import java.net.HttpURLConnection;
import java.net.URL;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of QueryService using the langchain4j library for chat
 */
@ApplicationScoped
@LangchainService
public class LangchainQueryService implements QueryService {

    private static final Logger LOG = Logger.getLogger(LangchainQueryService.class);

    private final OllamaStreamingChatModel chatModel;
    private final String ollamaModel;
    private final String ollamaEndpoint;

    @Inject
    public LangchainQueryService(
            OllamaStreamingChatModel chatModel,
            @ConfigProperty(name = "ollama.endpoint", defaultValue = "http://localhost:11434") String ollamaEndpoint,
            @ConfigProperty(name = "ollama.chat.model", defaultValue = "llama3") String ollamaModel) {
        this.chatModel = chatModel;
        this.ollamaModel = ollamaModel;
        this.ollamaEndpoint = ollamaEndpoint;
        
        LOG.infof("Created LangchainQueryService with model=%s, endpoint=%s", 
            ollamaModel, ollamaEndpoint);
            
        // Verify Ollama availability on initialization
        verifyOllamaServiceAvailability();
    }
    
    /**
     * Check if Ollama server is available and the model is loaded
     */
    private void verifyOllamaServiceAvailability() {
        try {
            URL url = new URL(ollamaEndpoint + "/api/tags");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            int responseCode = connection.getResponseCode();
            
            if (responseCode == 200) {
                LOG.infof("Ollama service is available at %s", ollamaEndpoint);
            } else {
                LOG.warnf("Ollama service returned unexpected response code: %d", responseCode);
            }
            
            // Check if model is loaded
            URL modelUrl = new URL(ollamaEndpoint + "/api/tags");
            HttpURLConnection modelConnection = (HttpURLConnection) modelUrl.openConnection();
            modelConnection.setRequestMethod("GET");
            
            int modelResponseCode = modelConnection.getResponseCode();
            if (modelResponseCode == 200) {
                LOG.infof("Ollama models endpoint is accessible, model %s should be loaded", ollamaModel);
            } else {
                LOG.warnf("Unable to verify model availability: %d", modelResponseCode);
            }
            
        } catch (Exception e) {
            LOG.errorf("Failed to verify Ollama service availability: %s", e.getMessage());
            LOG.warnf("Make sure Ollama is running and accessible at %s", ollamaEndpoint);
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
    @Timeout(value = 15, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 15000,
        successThreshold = 2)
    @CircuitBreakerName("ollama-query")
    @Fallback(fallbackMethod = "fallbackQuery")
    public Uni<String> query(String prompt, List<pro.olli.ai.ChatMessage> history) {
        LOG.infof("Querying Ollama with langchain4j model: %s", ollamaModel);
        
        // First check if the Ollama service is available
        try {
            URL healthUrl = new URL(ollamaEndpoint + "/api/tags");
            HttpURLConnection connection = (HttpURLConnection) healthUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                LOG.errorf("Ollama service is not available, response code: %d", responseCode);
                return fallbackQuery(prompt, history);
            }
        } catch (Exception e) {
            LOG.errorf("Failed to verify Ollama service before query: %s", e.getMessage());
            return fallbackQuery(prompt, history);
        }
        
        return Uni.createFrom().emitter(emitter -> {
            try {
                // Convert our ChatMessage format to langchain4j format
                List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
                
                // Add a system message to start
                messages.add(new SystemMessage("You are a helpful AI assistant."));
                
                // Add history messages
                if (history != null && !history.isEmpty()) {
                    for (pro.olli.ai.ChatMessage msg : history) {
                        if ("user".equalsIgnoreCase(msg.role)) {
                            messages.add(new UserMessage(msg.content));
                        } else {
                            messages.add(new AiMessage(msg.content));
                        }
                    }
                }
                
                // Add the current prompt as a user message
                messages.add(new UserMessage(prompt));
                
                LOG.debugf("Sending message to Ollama: %s", messages);
                
                // Create a StringBuilder to collect streaming response
                StringBuilder responseBuilder = new StringBuilder();
                
                // Create a CountDownLatch to wait for completion
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                
                // Handle exceptions in streaming
                final Exception[] exception = new Exception[1];
                
                try {
                    // Stream the chat response
                    chatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
                        @Override
                        public void onNext(String token) {
                            responseBuilder.append(token);
                        }
                        
                        public void onComplete(AiMessage message) {
                            latch.countDown();
                        }
                        
                        @Override
                        public void onError(Throwable error) {
                            LOG.errorf("Error in streaming response: %s", error.getMessage());
                            exception[0] = new Exception(error);
                            latch.countDown();
                        }
                    });
                } catch (Exception e) {
                    LOG.errorf("Failed to start streaming: %s", e.getMessage());
                    emitter.fail(new AppException("Failed to start streaming from Ollama", e,
                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
                    return;
                }
                
                // Wait for streaming to complete (with timeout)
                boolean completed = latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
                
                // Check if an exception occurred
                if (exception[0] != null) {
                    throw exception[0];
                }
                
                // Check if streaming timed out
                if (!completed) {
                    throw new AppException("Timed out waiting for Ollama chat response",
                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
                
                String content = responseBuilder.toString();
                if (content == null || content.isBlank()) {
                    throw new AppException("Ollama returned empty chat response",
                        AppException.ErrorType.EXTERNAL_SERVICE_ERROR);
                }
                
                LOG.infof("Generated chat response with Ollama: %s", 
                    content.length() > 100 ? content.substring(0, 100) + "..." : content);
                emitter.complete(content);
            } catch (Exception e) {
                LOG.errorf("Failed to generate chat response with Ollama: %s", e.getMessage());
                emitter.fail(new AppException("Failed to generate chat response with Ollama", e,
                    AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
            }
        });
    }

    public Uni<String> fallbackQuery(String prompt, List<pro.olli.ai.ChatMessage> history) {
        LOG.warnf("Using fallback query generation for prompt of length: %d", prompt.length());
        return Uni.createFrom().item(() -> {
            String fallbackResponse = "I'm sorry, but I'm currently experiencing technical difficulties. " +
                "Please try again in a few moments or contact support if the issue persists.";
            return fallbackResponse;
        });
    }
}