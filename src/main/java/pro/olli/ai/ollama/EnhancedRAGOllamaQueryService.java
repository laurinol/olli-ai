/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.ai.ollama;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import pro.olli.ai.AiConfiguredService;
import pro.olli.ai.AiImplementationType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.ai.ChatMessage;
import pro.olli.ai.EmbeddingService;
import pro.olli.ai.QueryService;
import pro.olli.ai.tokenizer.BytePairEncodingTokenizer;
import pro.olli.ai.utils.TextChunker;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.repository.SyncRepository;

import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Enhanced RAG implementation that uses document chunking and vector search
 * to improve context retrieval and query quality.
 */
@ApplicationScoped
public class EnhancedRAGOllamaQueryService implements QueryService {

    private static final Logger LOG = Logger.getLogger(EnhancedRAGOllamaQueryService.class);

    private final io.vertx.mutiny.core.Vertx vertx;
    private final String ollamaEndpoint;
    private final String ollamaModel;
    private final EmbeddingService embeddingService;
    private final SyncRepository syncRepository;

    // Configuration for RAG
    private final int maxContextTokens;
    private final int maxRelevantChunks;
    private final TextChunker.ChunkingStrategy chunkingStrategy;

    @Inject
    public EnhancedRAGOllamaQueryService(
            io.vertx.mutiny.core.Vertx vertx,
            @ConfigProperty(name = "ollama.endpoint", defaultValue = "http://localhost:11434") String ollamaEndpoint,
            @ConfigProperty(name = "ollama.chat.model", defaultValue = "tinyllama") String ollamaModel,
            @ConfigProperty(name = "ollama.rag.max-context-tokens", defaultValue = "2048") int maxContextTokens,
            @ConfigProperty(name = "ollama.rag.max-chunks", defaultValue = "5") int maxRelevantChunks,
            @ConfigProperty(name = "ollama.rag.chunking-strategy", defaultValue = "SENTENCE") String chunkingStrategyName,
            @AiConfiguredService EmbeddingService embeddingService,
            @Named("mongodb-sync") SyncRepository syncRepository) {
        this.vertx = vertx;
        this.ollamaEndpoint = ollamaEndpoint;
        this.ollamaModel = ollamaModel;
        this.embeddingService = embeddingService;
        this.syncRepository = syncRepository;
        this.maxContextTokens = maxContextTokens;
        this.maxRelevantChunks = maxRelevantChunks;

        // Parse chunking strategy from configuration
        TextChunker.ChunkingStrategy parsedStrategy;
        try {
            parsedStrategy = TextChunker.ChunkingStrategy.valueOf(chunkingStrategyName);
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid chunking strategy: %s, defaulting to SENTENCE", chunkingStrategyName);
            parsedStrategy = TextChunker.ChunkingStrategy.SENTENCE;
        }
        this.chunkingStrategy = parsedStrategy;

        LOG.infof("Created EnhancedRAGOllamaQueryService with model=%s, endpoint=%s, maxTokens=%d, maxChunks=%d, strategy=%s",
            ollamaModel, ollamaEndpoint, maxContextTokens, maxRelevantChunks, chunkingStrategy);
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
    @CircuitBreakerName("enhanced-rag-ollama-query")
    @Fallback(fallbackMethod = "fallbackQuery")
    public Uni<String> query(String prompt, List<ChatMessage> history) {
        LOG.infof("Querying with Enhanced RAG Ollama API: %s", ollamaModel);

        // Validate input
        if (prompt == null || prompt.trim().isEmpty()) {
            LOG.warn("Empty prompt provided for query");
            return Uni.createFrom().failure(
                new AppException("Empty prompt provided for query",
                    AppException.ErrorType.VALIDATION_ERROR));
        }

        // TODO: Get business ID and user ID from system properties
        // (in a real system this should come from the authentication context)
        String userId = System.getProperty("ollama.rag.user_id", "test-user");
        String businessId = System.getProperty("ollama.rag.business_id", "test-business");

        LOG.infof("Enhanced RAG query using businessId=%s, userId=%s", businessId, userId);

        // Generate embedding for the query
        return embeddingService.generateEmbedding(prompt)
            .flatMap(queryEmbedding -> {
                // Find similar documents
                return findRelevantContext(businessId, userId, queryEmbedding, prompt)
                    .flatMap(context -> executeQueryWithContext(prompt, history, context));
            });
    }

    /**
     * Find relevant context for the query by searching for similar document chunks
     */
    private Uni<String> findRelevantContext(String businessId, String userId,
                                          List<Float> queryEmbedding, String prompt) {
        // Search for similar documents
        return syncRepository.findSimilarDocuments(businessId, userId, queryEmbedding, maxRelevantChunks)
            .map(similarDocuments -> {
                if (similarDocuments.isEmpty()) {
                    LOG.info("No similar documents found for query");
                    return "";
                }

                LOG.infof("Found %d similar documents for query", similarDocuments.size());

                // Format the documents as context
                String context = "Based on your documents, here is relevant information:\n\n";

                for (int i = 0; i < similarDocuments.size(); i++) {
                    String doc = similarDocuments.get(i);
                    context += "Document " + (i + 1) + ": " + doc + "\n\n";
                }

                // Truncate context if it's too long
                if (BytePairEncodingTokenizer.getTokenCount(context) > maxContextTokens) {
                    LOG.infof("Context too long (%d tokens), truncating",
                        BytePairEncodingTokenizer.getTokenCount(context));
                    context = BytePairEncodingTokenizer.truncateText(context, maxContextTokens);
                }

                return context;
            });
    }

    /**
     * Execute query with retrieved context
     */
    private Uni<String> executeQueryWithContext(String prompt, List<ChatMessage> history, String context) {
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
        String systemPrompt = "You are a helpful AI assistant. ";

        // Add context if available
        if (context != null && !context.isEmpty()) {
            systemPrompt += "When answering, use ONLY the following information:\n\n" + context;
            systemPrompt += "\n\nIf the information provided doesn't answer the question, say you don't know rather than making up information.";
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
            .put("model", ollamaModel)
            .put("prompt", fullPrompt.toString())
            .put("stream", false);

        LOG.infof("Sending Enhanced RAG query with total prompt length: %d", fullPrompt.length());

        if (!context.isEmpty()) {
            LOG.infof("RAG context length: %d characters, ~%d tokens",
                context.length(), BytePairEncodingTokenizer.getTokenCount(context));
        }

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

                    LOG.infof("Generated Enhanced RAG query response with direct API: %s",
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
     * Process a document into chunks for storage
     * @param document The document text
     * @param filename The filename for metadata
     * @return List of chunks with metadata
     */
    public List<String> processDocumentIntoChunks(String document, String filename) {
        // Skip empty documents
        if (document == null || document.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // Chunk the document
        List<String> chunks = TextChunker.chunkText(document, chunkingStrategy,
                                                  TextChunker.DEFAULT_CHUNK_SIZE,
                                                  TextChunker.DEFAULT_CHUNK_OVERLAP);

        LOG.infof("Chunked document '%s' into %d chunks using strategy %s",
            filename, chunks.size(), chunkingStrategy);

        // Add metadata to chunks
        return TextChunker.addMetadataToChunks(chunks, filename, null);
    }

    /**
     * Store document chunks and their embeddings
     * @param businessId The business ID
     * @param userId The user ID
     * @param filename The filename
     * @param documentText The document text
     * @return Uni<Void> when complete
     */
    public Uni<Void> storeDocumentChunks(String businessId, String userId, String filename, String documentText) {
        // Process document into chunks
        List<String> chunks = processDocumentIntoChunks(documentText, filename);

        if (chunks.isEmpty()) {
            LOG.infof("No chunks generated for document: %s", filename);
            return Uni.createFrom().voidItem();
        }

        LOG.infof("Storing %d chunks for document: %s", chunks.size(), filename);

        // Process each chunk
        List<Uni<Void>> storeOperations = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String chunkId = filename + "-chunk-" + i;

            // Generate embedding for the chunk
            Uni<Void> storeOp = embeddingService.generateEmbedding(chunk)
                .flatMap(embedding ->
                    // Store the embedding
                    syncRepository.storeEmbedding(chunkId, businessId, userId, chunk, embedding)
                );

            storeOperations.add(storeOp);
        }

        // Combine all operations
        return Uni.combine().all().unis(storeOperations)
            .discardItems();
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
    @Timed(value = "enhanced-rag-ollama.query.health.check", type = Timed.OperationType.EXTERNAL_SERVICE)
    public Uni<Boolean> healthCheck() {
        LOG.infof("Checking Enhanced RAG Ollama query service health at endpoint: %s with model: %s", ollamaEndpoint, ollamaModel);

        return query("test", List.of())
            .map(response -> response != null && !response.isEmpty() &&
                !response.contains("I'm sorry, but I'm currently experiencing technical difficulties"))
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Health check failed: %s", e.getMessage());
                return false;
            });
    }
}
