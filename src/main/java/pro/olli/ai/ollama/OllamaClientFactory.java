/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */

package pro.olli.ai.ollama;

import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pro.olli.ai.AiConfiguredService;
import pro.olli.ai.ollama.LangchainService;
import pro.olli.ai.ollama.OllamaImplementation;
import pro.olli.core.resilience.exception.AppException;

/**
 * Factory for creating Ollama clients and APIs
 */
@ApplicationScoped
public class OllamaClientFactory {

    private static final Logger LOG = Logger.getLogger(OllamaClientFactory.class);

    @ConfigProperty(name = "ollama.endpoint", defaultValue = "http://localhost:11434")
    String ollamaEndpoint;

    @ConfigProperty(name = "ollama.embedding.model", defaultValue = "tinyllama")
    String ollamaEmbeddingModel;

    @ConfigProperty(name = "ollama.embedding.dimension", defaultValue = "768")
    int embeddingDimension;

    @ConfigProperty(name = "ollama.chat.model", defaultValue = "llama3")
    String ollamaChatModel;

    /**
     * Creates an Ollama Embedding Model client
     */
    @Produces
    @ApplicationScoped
    public OllamaEmbeddingModel createOllamaEmbeddingModel() {
        LOG.infof("Creating Ollama embedding model client with endpoint: %s, model: %s",
            ollamaEndpoint, ollamaEmbeddingModel);
        try {
            return createOllamaEmbeddingModel(ollamaEndpoint, ollamaEmbeddingModel);
        } catch (Exception e) {
            LOG.errorf("Failed to initialize Ollama embedding model: %s", e.getMessage());
            throw new AppException("Failed to initialize Ollama embedding model", e,
                AppException.ErrorType.CONFIGURATION_ERROR);
        }
    }

    /**
     * Creates an Ollama Streaming Chat Model client
     */
    @Produces
    @ApplicationScoped
    public OllamaStreamingChatModel createOllamaChatModel() {
        LOG.infof("Creating Ollama chat model client with endpoint: %s, model: %s",
            ollamaEndpoint, ollamaChatModel);
        try {
            return createOllamaChatModel(ollamaEndpoint, ollamaChatModel);
        } catch (Exception e) {
            LOG.errorf("Failed to initialize Ollama chat model: %s", e.getMessage());
            throw new AppException("Failed to initialize Ollama chat model", e,
                AppException.ErrorType.CONFIGURATION_ERROR);
        }
    }
    
    /**
     * Static factory method to create an Ollama embedding model
     */
    public static OllamaEmbeddingModel createOllamaEmbeddingModel(String endpoint, String modelName) {
        return OllamaEmbeddingModel.builder()
            .baseUrl(endpoint)
            .modelName(modelName)
            .timeout(java.time.Duration.ofSeconds(10))
            .build();
    }
    
    /**
     * Static factory method to create an Ollama chat model
     */
    public static OllamaStreamingChatModel createOllamaChatModel(String endpoint, String modelName) {
        LOG.infof("Creating OllamaStreamingChatModel with endpoint: %s, model: %s", endpoint, modelName);
        
        // Verify endpoint is accessible
        try {
            java.net.URL url = new java.net.URL(endpoint + "/api/tags");
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            
            int responseCode = connection.getResponseCode();
            LOG.infof("Ollama endpoint test - response code: %d", responseCode);
            
            if (responseCode != 200) {
                LOG.warnf("Ollama server at %s returned unexpected status code: %d", endpoint, responseCode);
            }
        } catch (Exception e) {
            LOG.errorf("Failed to validate Ollama endpoint %s: %s", endpoint, e.getMessage());
            LOG.warnf("Make sure Ollama is running and accessible. You can start it with: test/config/start-ollama.sh");
        }
        
        try {
            return OllamaStreamingChatModel.builder()
                .baseUrl(endpoint)
                .modelName(modelName)
                .timeout(java.time.Duration.ofSeconds(15))
                .build();
        } catch (Exception e) {
            LOG.errorf("Error building OllamaStreamingChatModel: %s", e.getMessage());
            throw new RuntimeException("Failed to build OllamaStreamingChatModel", e);
        }
    }

    
    /**
     * Creates a langchain4j-based embedding service
     */
    @Produces
    @ApplicationScoped
    public LangchainEmbeddingService createLangchainEmbeddingService() {
        LOG.infof("Creating LangchainEmbeddingService with model: %s", ollamaEmbeddingModel);
        OllamaEmbeddingModel model = createOllamaEmbeddingModel();
        return new LangchainEmbeddingService(model, ollamaEndpoint, ollamaEmbeddingModel, embeddingDimension);
    }
    
    /**
     * Creates a langchain4j-based query service
     */
    @Produces
    @ApplicationScoped
    public LangchainQueryService createLangchainQueryService() {
        LOG.infof("Creating LangchainQueryService with model: %s", ollamaChatModel);
        OllamaStreamingChatModel model = createOllamaChatModel();
        return new LangchainQueryService(model, ollamaEndpoint, ollamaChatModel);
    }
}
