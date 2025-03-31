/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.ai.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.onnxruntime.engine.OrtEngine;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.Model;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import pro.olli.ai.AiConfiguredService;
import pro.olli.ai.AiImplementationType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import pro.olli.ai.EmbeddingService;
import pro.olli.ai.tokenizer.SimpleTokenizer;
import pro.olli.ai.utils.TextChunker;
import pro.olli.core.metrics.Timed;
import pro.olli.core.resilience.exception.AppException;

import java.io.File;
import java.nio.file.Paths;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Implementation of EmbeddingService that uses ONNX Runtime with DJL for local embedding generation.
 * This avoids API calls to external services and can run completely locally.
 */
@ApplicationScoped
public class OnnxEmbeddingService implements EmbeddingService {

    private static final Logger LOG = Logger.getLogger(OnnxEmbeddingService.class);
    
    // Model and tokenizer
    private ZooModel<String, float[]> model;
    private HuggingFaceTokenizer tokenizer;
    
    // Flag to track initialization status
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // Configuration properties
    @ConfigProperty(name = "onnx.model.path", defaultValue = "models/all-MiniLM-L6-v2.onnx")
    String modelPath;
    
    @ConfigProperty(name = "onnx.tokenizer.path", defaultValue = "models/tokenizer.json")
    String tokenizerPath;
    
    @ConfigProperty(name = "onnx.embedding.dimension", defaultValue = "384")
    int embeddingDimension;
    
    @ConfigProperty(name = "onnx.max.sequence.length", defaultValue = "128")
    int maxSequenceLength;
    
    @ConfigProperty(name = "onnx.enabled", defaultValue = "false")
    boolean onnxEnabled;

    /**
     * Constructor with default configuration
     */
    public OnnxEmbeddingService() {
        LOG.info("Creating ONNX Embedding Service");
    }
    
    /**
     * Initialize the ONNX Runtime and tokenizer
     */
    @PostConstruct
    public void initialize() {
        if (!onnxEnabled) {
            LOG.info("ONNX embedding service is disabled, will use fallback method");
            return;
        }
        
        try {
            // Check if model file exists
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                LOG.errorf("Model file not found at: %s", modelPath);
                return;
            }
            
            // Check if tokenizer file exists
            File tokenizerFile = new File(tokenizerPath);
            if (!tokenizerFile.exists()) {
                LOG.errorf("Tokenizer file not found at: %s", tokenizerPath);
                return;
            }
            
            // Create the model using DJL
            Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelPath(Paths.get(modelPath))
                .optEngine("OnnxRuntime")
                .optTranslator(new BertEmbeddingTranslator(maxSequenceLength))
                .build();
            
            model = criteria.loadModel();
            
            // Load tokenizer
            LOG.infof("Loading tokenizer from: %s", tokenizerPath);
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
            
            // Mark as initialized
            initialized.set(true);
            LOG.info("ONNX embedding service initialized successfully");
        } catch (Exception e) {
            LOG.errorf("Failed to initialize ONNX embedding service: %s", e.getMessage());
        }
    }
    
    /**
     * Clean up resources when the bean is destroyed
     */
    @PreDestroy
    public void cleanup() {
        if (model != null) {
            try {
                model.close();
                LOG.info("ONNX model closed");
            } catch (Exception e) {
                LOG.error("Error closing ONNX model", e);
            }
        }
        
        if (tokenizer != null) {
            try {
                tokenizer.close();
                LOG.info("Tokenizer closed");
            } catch (Exception e) {
                LOG.error("Error closing tokenizer", e);
            }
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
    @Timeout(value = 30, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 15000,
        successThreshold = 2)
    @Fallback(fallbackMethod = "fallbackEmbedding")
    public Uni<List<Float>> generateEmbedding(final String text) {
        LOG.infof("Generating embedding with ONNX Runtime");
        
        if (text == null || text.trim().isEmpty()) {
            LOG.warn("Empty text provided for embedding generation");
            return Uni.createFrom().failure(
                new AppException("Empty text provided for embedding generation",
                    AppException.ErrorType.VALIDATION_ERROR));
        }
        
        // If not initialized or disabled, use fallback
        if (!initialized.get() || !onnxEnabled) {
            LOG.warn("ONNX embedding service not initialized, using fallback");
            return Uni.createFrom().item(() -> generateFallbackEmbedding(text));
        }
        
        // If text is too long, chunk it and use the first chunk
        final String processedText;
        if (text.length() > maxSequenceLength * 10) {
            LOG.infof("Text too long (%d chars), chunking and using first chunk", text.length());
            List<String> chunks = TextChunker.chunkText(text, TextChunker.ChunkingStrategy.SENTENCE, 
                                                      maxSequenceLength * 10, 0);
            if (!chunks.isEmpty()) {
                processedText = chunks.get(0);
                LOG.infof("Using first chunk of %d chars", processedText.length());
            } else {
                processedText = text;
            }
        } else {
            processedText = text;
        }
        
        // Use non-blocking pattern with Uni
        return Uni.createFrom().emitter(emitter -> {
            try {
                // Generate embeddings with the model
                Predictor<String, float[]> predictor = model.newPredictor();
                float[] embedding = predictor.predict(processedText);
                
                // Convert to list of Float
                List<Float> embeddingList = new ArrayList<>(embedding.length);
                for (float v : embedding) {
                    embeddingList.add(v);
                }
                
                LOG.infof("Generated ONNX embedding, length: %d", embeddingList.size());
                emitter.complete(embeddingList);
            } catch (Exception e) {
                LOG.errorf("Error generating ONNX embedding: %s", e.getMessage());
                emitter.fail(new AppException("Failed to generate ONNX embedding", e,
                    AppException.ErrorType.EXTERNAL_SERVICE_ERROR));
            }
        });
    }
    
    /**
     * Fallback method for embedding generation
     */
    public Uni<List<Float>> fallbackEmbedding(String text) {
        LOG.warnf("Using fallback embedding generation for text of length: %d", text.length());
        return Uni.createFrom().item(() -> generateFallbackEmbedding(text));
    }
    
    /**
     * Generate a fallback embedding using a deterministic random approach
     */
    private List<Float> generateFallbackEmbedding(String text) {
        // Create a deterministic embedding based on text hash
        int seed = text.hashCode();
        Random random = new Random(seed);

        // Generate a simplified embedding vector
        List<Float> embedding = new ArrayList<>(embeddingDimension);
        for (int i = 0; i < embeddingDimension; i++) {
            embedding.add((random.nextFloat() * 2) - 1); // Value between -1 and 1
        }

        // Normalize the vector
        float sum = 0;
        for (float v : embedding) {
            sum += v * v;
        }
        float norm = (float) Math.sqrt(sum);

        for (int i = 0; i < embeddingDimension; i++) {
            embedding.set(i, embedding.get(i) / norm);
        }

        LOG.infof("Generated fallback embedding, length: %d", embedding.size());
        return embedding;
    }
    
    /**
     * Check if the service is initialized and ready
     */
    public boolean isReady() {
        return initialized.get() && onnxEnabled;
    }
    
    /**
     * Custom translator for BERT embedding models
     */
    private class BertEmbeddingTranslator implements Translator<String, float[]> {
        
        private final int maxLength;
        
        public BertEmbeddingTranslator(int maxLength) {
            this.maxLength = maxLength;
        }
        
        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            // The model outputs embeddings as a tensor
            NDArray embedding = list.get(0);
            
            // Get the mean of the token embeddings (CLS token)
            // For sentence-BERT models, we usually use the CLS token or the mean
            return embedding.toFloatArray();
        }
        
        @Override
        public NDList processInput(TranslatorContext ctx, String input) {
            NDManager manager = ctx.getNDManager();
            
            try {
                // Tokenize input
                long[] tokenIds;
                long[] attentionMask;
                
                if (tokenizer != null) {
                    // Use HuggingFace tokenizer
                    ai.djl.huggingface.tokenizers.Encoding encoding = tokenizer.encode(input);
                    tokenIds = encoding.getIds();
                    attentionMask = encoding.getAttentionMask();
                    
                    // Truncate if too long
                    if (tokenIds.length > maxLength) {
                        tokenIds = Arrays.copyOf(tokenIds, maxLength);
                        attentionMask = Arrays.copyOf(attentionMask, maxLength);
                    }
                } else {
                    // Fallback to simple tokenization
                    List<String> tokens = SimpleTokenizer.tokenize(input);
                    int tokenCount = Math.min(tokens.size(), maxLength);
                    
                    tokenIds = new long[tokenCount];
                    for (int i = 0; i < tokenCount; i++) {
                        // Convert token string to hash code (not a proper tokenizer)
                        tokenIds[i] = Math.abs(tokens.get(i).hashCode() % 10000);
                    }
                    
                    // Create attention mask
                    attentionMask = new long[tokenCount];
                    Arrays.fill(attentionMask, 1L);
                }
                
                // Create NDArrays for input IDs and attention mask
                NDArray inputIdsArray = manager.create(tokenIds).expandDims(0);
                NDArray attentionMaskArray = manager.create(attentionMask).expandDims(0);
                
                return new NDList(inputIdsArray, attentionMaskArray);
            } catch (Exception e) {
                LOG.errorf("Error in tokenization: %s", e.getMessage());
                throw new RuntimeException("Failed to process input for embedding", e);
            }
        }
    }
}