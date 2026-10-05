/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.ai.ollama;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import pro.olli.ai.utils.TextChunker.ChunkingStrategy;
import pro.olli.config.AppConfig;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;
import pro.olli.repository.SyncRepository;

/**
 * Enhanced RAG implementation that uses document chunking and vector search to improve context retrieval and query
 * quality.
 */
public class EnhancedRAGOllamaQueryService implements QueryService {

    private static final Logger LOG = Logger.getLogger(EnhancedRAGOllamaQueryService.class);

    private final AppConfig config;
    private final Vertx vertx;
    private final EmbeddingService embeddingService;
    private final SyncRepository syncRepository;
    private final ChunkingStrategy chunkingStrategy;
    private final pro.olli.ai.tokenizer.BytePairEncodingTokenizer tokenizer;

    @Inject
    public EnhancedRAGOllamaQueryService(AppConfig config,
        Vertx vertx,
        EmbeddingService embeddingService,
        SyncRepository syncRepository,
        BytePairEncodingTokenizer tokenizer) {
        this.tokenizer = tokenizer;
        this.config = config;
        this.vertx = vertx;
        this.embeddingService = embeddingService;
        this.syncRepository = syncRepository;

        // Parse chunking strategy from configuration
        TextChunker.ChunkingStrategy parsedStrategy;
        try {
            parsedStrategy = TextChunker.ChunkingStrategy.valueOf(config.getOllamaRagChunkingStrategy());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid chunking strategy: %s, defaulting to SENTENCE", config.getOllamaRagChunkingStrategy());
            parsedStrategy = TextChunker.ChunkingStrategy.SENTENCE;
        }
        this.chunkingStrategy = parsedStrategy;

        LOG.infof(
            "Created EnhancedRAGOllamaQueryService with model=%s, endpoint=%s, maxTokens=%d, maxChunks=%d, strategy=%s",
            config.getOllamaChatModel(), config.getOllamaEndpoint(), config.getOllamaRagMaxContextTokens(),
            config.getOllamaRagMaxChunks(), chunkingStrategy);
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
        LOG.infof("Querying with Enhanced RAG Ollama API: %s", config.getOllamaChatModel());

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
        return syncRepository.findSimilarDocuments(businessId, userId, queryEmbedding, config.getOllamaRagMaxChunks())
            .map(similarDocuments -> {
                if (similarDocuments.isEmpty()) {
                    LOG.info("No similar documents found for query");
                    return "";
                }

                LOG.infof("Found %d similar documents or chunks for query", similarDocuments.size());

                // Format the document chunks as context
                StringBuilder contextBuilder = new StringBuilder();

                // Standard document formatting - note that the documents are actually text chunks
                contextBuilder.append("Here is relevant information from your documents:\n\n");

                // Determine if we're getting file paths (MongoDB) or content chunks (MemoryRepo)
                boolean containsActualContent = false;

                // Check if we need to fetch content or already have content chunks
                // Simplistic check: if any string contains newlines or is longer than 100 chars,
                // it's probably content rather than a filepath
                for (String doc : similarDocuments) {
                    if (doc.contains("\n") || doc.length() > 100) {
                        containsActualContent = true;
                        break;
                    }
                }

                if (containsActualContent) {
                    // We have actual content chunks, not just file paths
                    for (int i = 0; i < similarDocuments.size(); i++) {
                        String chunk = similarDocuments.get(i);

                        // Remove metadata prefix if present
                        if (chunk.startsWith("IMPORTANT - ")) {
                            chunk = chunk.substring("IMPORTANT - ".length());
                        }

                        // Add to context with clear separation
                        contextBuilder.append("CHUNK ").append(i + 1).append(":\n");
                        contextBuilder.append(chunk).append("\n\n");
                    }
                } else {
                    // We have file paths - log the issue since we cannot provide actual content
                    LOG.warn("findSimilarDocuments returned only file paths, not content chunks. RAG will be ineffective.");
                    contextBuilder.append("WARNING: Only document paths were retrieved, not content. This likely indicates a misconfiguration.\n\n");

                    for (int i = 0; i < similarDocuments.size(); i++) {
                        contextBuilder.append("Document ").append(i + 1).append(": ").append(similarDocuments.get(i)).append("\n");
                    }

                    contextBuilder.append("\nPlease inform the administrator that the RAG system is returning file paths instead of content chunks.");
                }

                String context = contextBuilder.toString();

                // Truncate context if it's too long
                int contextTokens = tokenizer.estimateTokenCount(context);
                if (contextTokens > config.getOllamaRagMaxContextTokens()) {
                    LOG.infof("Context too long (%d tokens), truncating",
                        contextTokens);
                    context = tokenizer.truncateToTokenLimit(context, config.getOllamaRagMaxContextTokens());
                }

                LOG.infof("Generated context with %d tokens from %d document chunks",
                    tokenizer.estimateTokenCount(context), similarDocuments.size());

                return context;
            });
    }

    /**
     * Execute query with retrieved context
     */
    private Uni<String> executeQueryWithContext(String prompt, List<ChatMessage> history, String context) {
        // Use WebClient to make a non-blocking request
        WebClient webClient = WebClient.create(vertx);

        LOG.debugf("Executing RAG query \"%s\", %d, %s", prompt, history.size(), context);

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

        // Add context if available
        if (context != null && !context.isEmpty()) {
            systemPrompt = "You are an AI assistant that answers questions based ONLY on the provided context. " +
                "The context is:\n\n" + context + "\n\n" +
                "IMPORTANT INSTRUCTIONS:\n" +
                "1. ONLY use information from the context to answer questions.\n" +
                "2. If the context does not have the information needed to answer a question, say 'I don't have information about that in the provided context.'\n" +
                "3. Do not use external information or make up facts that are not in the context.\n" +
                "4. For questions about dates, details, statistics, or any specific information, ONLY use the exact information from the context chunks.\n" +
                "5. Be concise and direct in your answers.\n" +
                "6. ALWAYS begin your answer with 'Based on the provided documents' or 'According to the documents'.\n" +
                "7. For names, dates, and numbers, be exact and precise - use the specific values from the context.\n" +
                "8. Each CHUNK may contain different information - read all chunks thoroughly to find the information relevant to the question.\n" +
                "9. Cite specific chunks when possible, e.g., 'According to CHUNK 3...'\n\n";
        }

        StringBuilder fullPrompt = new StringBuilder(systemPrompt);

        // Add chat history
        if (history != null && !history.isEmpty()) {
            for (ChatMessage msg : history) {
                String role = msg.role.trim().toLowerCase();
                switch (role) {
                    case "user" -> fullPrompt.append("\n\nUser: ").append(msg.content);
                    case "assistant" -> fullPrompt.append("\n\nAssistant: ").append(msg.content);
                    case "system" ->
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

        LOG.infof("Sending Enhanced RAG query with total prompt length: %d", fullPrompt.length());

        if (context != null && !context.isEmpty()) {
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
     *
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
     *
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
                "1. Ollama is not running or is unreachable at " + config.getOllamaEndpoint() + "\n" +
                "2. The required model '" + config.getOllamaChatModel() + "' has not been pulled\n\n" +
                "Please check the server logs for more detailed information.";
            return fallbackResponse;
        });
    }

    /**
     * Health check method
     */
    @Timed(value = "enhanced-rag-ollama.query.health.check", type = Timed.OperationType.EXTERNAL_SERVICE)
    public Uni<Boolean> healthCheck() {
        LOG.infof("Checking Enhanced RAG Ollama query service health at endpoint: %s with model: %s",
            config.getOllamaEndpoint(), config.getOllamaChatModel());

        return query("test", List.of())
            .map(response -> response != null && !response.isEmpty() &&
                !response.contains("I'm sorry, but I'm currently experiencing technical difficulties"))
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("Health check failed: %s", e.getMessage());
                return false;
            });
    }
}
