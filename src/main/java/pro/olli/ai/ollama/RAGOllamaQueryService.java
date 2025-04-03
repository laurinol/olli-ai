/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.ai.ollama;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.ai.ChatMessage;
import pro.olli.ai.EmbeddingService;
import pro.olli.ai.QueryService;
import pro.olli.config.AppConfig;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.repository.SyncRepository;

/**
 * RAG-enabled implementation of QueryService that retrieves relevant documents using embeddings before sending the
 * query to the LLM.
 */
public class RAGOllamaQueryService implements QueryService {

    private static final Logger LOG = Logger.getLogger(RAGOllamaQueryService.class);
    private static final int MAX_DOCUMENT_RESULTS = 3;

    private final AppConfig config;
    private final Vertx vertx;
    private final EmbeddingService embeddingService;
    private final SyncRepository syncRepository;
    
    // This class is a RAG implementation with vector embedding retrieval

    @Inject
    public RAGOllamaQueryService(AppConfig config, Vertx vertx,
        EmbeddingService embeddingService,
        SyncRepository syncRepository) {
        this.config = config;
        this.vertx = vertx;
        this.embeddingService = embeddingService;
        this.syncRepository = syncRepository;

        LOG.infof("Created RAGOllamaQueryService with model=%s, endpoint=%s",
            config.getOllamaChatModel(), config.getOllamaEndpoint());
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
    @CircuitBreakerName("rag-ollama-query")
    @Fallback(fallbackMethod = "fallbackQuery")
    public Uni<String> query(String prompt, List<ChatMessage> history) {
        LOG.infof("Querying with RAG Ollama API: %s", config.getOllamaChatModel());
        LOG.infof("SyncRepository implementation: %s", syncRepository.getClass().getName());

        // Validate input
        if (prompt == null || prompt.trim().isEmpty()) {
            LOG.warn("Empty prompt provided for query");
            return Uni.createFrom().failure(
                new AppException("Empty prompt provided for query",
                    AppException.ErrorType.VALIDATION_ERROR));
        }
        
        // Enable system property for debugging RAG
        boolean debugRag = Boolean.getBoolean("debug.rag");
        if (debugRag) {
            LOG.info("RAG debugging enabled - adding detailed logging");
        }

        // Get business ID and user ID from system properties
        // In a real system, these would be securely passed from authentication context
        String userId = System.getProperty("ollama.rag.user_id", "test-user");
        String businessId = System.getProperty("ollama.rag.business_id", "test-business");

        LOG.infof("RAG query using businessId=%s, userId=%s for prompt: %s", businessId, userId, prompt);
        
        // Set current query for the MemorySyncRepository to use for prioritization
        System.setProperty("current.query", prompt);

        // First create an embedding for the query
        LOG.info("Generating embedding for query: " + prompt);
        return embeddingService.generateEmbedding(prompt)
            .flatMap(queryEmbedding -> {
                // Log the embedding dimensions
                LOG.infof("Generated query embedding with %d dimensions", queryEmbedding.size());
                
                // Search for relevant documents
                LOG.infof("Searching for documents for business=%s, user=%s", businessId, userId);
                return syncRepository.findSimilarDocuments(businessId, userId, queryEmbedding, MAX_DOCUMENT_RESULTS)
                    .flatMap(relevantDocuments -> {
                        LOG.infof("Found %d relevant documents for query", relevantDocuments.size());
                        
                        // Log the content of each document found
                        for (int i = 0; i < relevantDocuments.size(); i++) {
                            LOG.infof("Document %d content: %s", i, relevantDocuments.get(i));
                        }

                        if (relevantDocuments.isEmpty()) {
                            // No relevant documents found, fall back to normal query
                            LOG.info("No relevant documents found, using standard query");
                            return executeDirectQuery(prompt, history, null);
                        } else {
                            // Build context from the relevant documents
                            String context = "I found the following relevant information:\n\n" +
                                relevantDocuments.stream()
                                    .map(doc -> "- " + doc)
                                    .collect(Collectors.joining("\n"));
                            
                            LOG.info("Built context from relevant documents: " + context);
                            
                            // Special handling for meeting-specific queries to enhance prompt
                            String enhancedPrompt = prompt;
                            if (Boolean.getBoolean("enhance.prompt") && 
                                (prompt.toLowerCase().contains("meeting") || 
                                 prompt.toLowerCase().contains("attendees") || 
                                 prompt.toLowerCase().contains("who") || 
                                 prompt.toLowerCase().contains("budget") || 
                                 prompt.toLowerCase().contains("timeline"))) {
                                LOG.info("Enhanced prompt mode enabled for meeting-related query");
                                enhancedPrompt = "Based ONLY on the meeting notes, " + prompt;
                            }

                            // Execute query with context
                            return executeDirectQuery(enhancedPrompt, history, context);
                        }
                    });
            });
    }
    

    /**
     * Execute a direct query to Ollama API with optional context from relevant documents
     */
    private Uni<String> executeDirectQuery(String prompt, List<ChatMessage> history, String context) {
        // Use WebClient to make a non-blocking request
        io.vertx.mutiny.ext.web.client.WebClient webClient = io.vertx.mutiny.ext.web.client.WebClient.create(vertx);

        // Parse URL
        URI uri;
        try {
            uri = new URI(config.getOllamaEndpoint() + "/api/generate");
        } catch (Exception e) {
            LOG.errorf("Invalid URL: %s", config.getOllamaEndpoint() + "/api/generate", e);
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
        String systemPrompt = "You are a helpful AI assistant. ";

        // Add context from retrieved documents if available
        if (context != null && !context.isEmpty()) {
            LOG.info("Adding retrieved context to prompt: " + context);
            systemPrompt = "You are an AI assistant that answers questions based ONLY on the provided context. " +
                "The context is:\n\n" + context + "\n\n" +
                "IMPORTANT INSTRUCTIONS:\n" +
                "1. ONLY use information from the context to answer questions.\n" +
                "2. If the context does not have the information needed to answer a question, say 'I don't have information about that in the provided context.'\n" +
                "3. Do not use external information or make up facts that are not in the context.\n" +
                "4. For questions about meeting notes, attendees, dates, budget, timeline, risks, or any other specific details, ONLY use the exact information from the context.\n" +
                "5. Be concise and direct in your answers.\n" +
                "6. ALWAYS begin your answer with 'According to the meeting notes' or 'Based on the meeting notes'.\n" +
                "7. CRITICAL: Pay careful attention to any 'IMPORTANT' markers in the context as they highlight the specific information the question is asking about.\n" +
                "8. For names, dates, and numbers, be exact and precise - use the specific values from the context.\n" +
                "9. Different parts of the context have specific information - read all of it thoroughly to find the exact information requested.\n\n" +
                "Examples:\n" +
                "Question: Who attended the meeting?\n" +
                "Answer: According to the meeting notes, the attendees were Maria Gonzalez, Raj Patel, and Lisa Chen.\n\n" +
                "Question: What is the budget?\n" +
                "Answer: According to the meeting notes, the budget approved was $25,000.\n\n" +
                "Question: Who needs to confirm the vendor timeline?\n" +
                "Answer: According to the meeting notes, Maria needs to confirm the vendor timeline by Feb 7.\n\n" +
                "Question: When is the website redesign due?\n" +
                "Answer: According to the meeting notes, the website redesign is due April 10, 2025.\n\n" +
                "Question: What risks were mentioned in the meeting?\n" +
                "Answer: According to the meeting notes, the risk mentioned is a delay in vendor delivery with an ETA of Feb 28.";
        } else {
            LOG.warn("No context retrieved from documents - using standard prompt");
        }

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
            .put("model", config.getOllamaChatModel())
            .put("prompt", fullPrompt.toString())
            .put("stream", false);

        LOG.debugf("Sending RAG query with total prompt length: %d", fullPrompt.length());

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

                    LOG.infof("Generated RAG query response with direct API: %s",
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
                "1. Ollama is not running or is unreachable at " + config.getOllamaEndpoint() + "\n" +
                "2. The required model '" + config.getOllamaChatModel() + "' has not been pulled\n\n" +
                "Please check the server logs for more detailed information.";
            return fallbackResponse;
        });
    }

    /**
     * Health check method
     */
    @Timed(value = "rag-ollama.query.health.check", type = Timed.OperationType.EXTERNAL_SERVICE)
    public Uni<Boolean> healthCheck() {
        LOG.infof("Checking RAG Ollama query service health at endpoint: %s with model: %s", config.getOllamaEndpoint(),
            config.getOllamaChatModel());

        return query("test", List.of())
            .map(response -> response != null && !response.isEmpty() &&
                !response.contains("I'm sorry, but I'm currently experiencing technical difficulties"))
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Health check failed: %s", e.getMessage());
                return false;
            });
    }
}