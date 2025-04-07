/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.ai.tokenizer;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.Encoding;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import pro.olli.config.AppConfig;

/**
 * Tokenizer implementation using Byte Pair Encoding (BPE) for accurate token calculations.
 * This implementation uses HuggingFace tokenizers for BPE implementation.
 */
@ApplicationScoped
public class BytePairEncodingTokenizer implements Tokenizer {
    private static final Logger LOG = Logger.getLogger(BytePairEncodingTokenizer.class);

    private final AppConfig config;
    private HuggingFaceTokenizer tokenizer;
    private final Map<String, Integer> tokenCountCache = new ConcurrentHashMap<>();
    private boolean initialized = false;

    /**
     * Default constructor required for CDI
     * Attempts basic initialization with default paths
     */
    public BytePairEncodingTokenizer() {
        LOG.debug("Default constructor called - attempting basic initialization");
        this.config = null;

        // Try to initialize with resources from classpath
        try {
            // Get resource from classpath
            java.net.URL resource = Thread.currentThread().getContextClassLoader().getResource("models/tokenizer.json");
            if (resource != null) {
                String path = resource.getPath();
                LOG.info("Attempting initialization with resource path: " + path);
                this.tokenizer = HuggingFaceTokenizer.newInstance(java.nio.file.Paths.get(java.net.URI.create(resource.toString())));
                this.initialized = true;
                LOG.info("Resource-based initialization successful");
            } else {
                LOG.warn("Resource models/tokenizer.json not found on classpath");
            }
        } catch (Exception e) {
            LOG.warn("Resource-based initialization failed, will use string-based tokenization", e);
        }
    }

    /**
     * Constructor for the BytePairEncodingTokenizer with configuration.
     *
     * @param config The application configuration
     */
    @Inject
    public BytePairEncodingTokenizer(AppConfig config) {
        LOG.debug("Injected constructor called");
        this.config = config;
    }

    /**
     * Initialize the tokenizer
     */
    @PostConstruct
    public void initialize() {
        // If already initialized, return
        if (initialized && tokenizer != null) {
            return;
        }
        
        // First try to use config path if available
        if (config != null) {
            try {
                String tokenPath = config.getAiTokenizerPath();
                LOG.info("Initializing BytePairEncodingTokenizer with configured path: " + tokenPath);
                
                // Try to get it as a resource from classpath first
                java.net.URL resource = Thread.currentThread().getContextClassLoader().getResource(tokenPath);
                if (resource != null) {
                    LOG.info("Using resource from classpath: " + resource);
                    this.tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(java.net.URI.create(resource.toString())));
                    this.initialized = true;
                    LOG.info("BytePairEncodingTokenizer successfully initialized from classpath resource");
                    return;
                }
                
                // Try with file path
                java.io.File file = new java.io.File(tokenPath);
                if (file.exists()) {
                    LOG.info("Using file from path: " + file.getAbsolutePath());
                    this.tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(file.getAbsolutePath()));
                    this.initialized = true;
                    LOG.info("BytePairEncodingTokenizer successfully initialized from file");
                    return;
                }
                
                LOG.warn("Config path not found as resource or file: " + tokenPath);
            } catch (Exception e) {
                LOG.error("Failed to initialize tokenizer with config path", e);
            }
        }
        
        // Fallback to resource from classpath
        try {
            // Get default resource from classpath
            java.net.URL resource = Thread.currentThread().getContextClassLoader().getResource("models/tokenizer.json");
            if (resource != null) {
                LOG.info("Fallback to default resource: " + resource);
                this.tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(java.net.URI.create(resource.toString())));
                this.initialized = true;
                LOG.info("BytePairEncodingTokenizer successfully initialized with default resource");
                return;
            }
            LOG.warn("Default resource models/tokenizer.json not found");
        } catch (Exception e) {
            LOG.error("Failed to initialize tokenizer with default resource", e);
        }
        
        LOG.warn("All tokenizer initialization attempts failed, will use fallback methods");
    }

    /**
     * Tokenizes the input text using BPE.
     *
     * @param text The text to tokenize
     * @return A list of token IDs
     */
    public List<Integer> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        if (!initialized || tokenizer == null) {
            LOG.warn("Tokenizer not initialized, using fallback tokenization");
            return fallbackTokenize(text);
        }

        try {
            Encoding encoding = tokenizer.encode(text);
            long[] tokens = encoding.getIds();
            List<Integer> result = new ArrayList<>(tokens.length);
            for (long token : tokens) {
                result.add((int) token);
            }
            return result;
        } catch (Exception e) {
            LOG.error("Error tokenizing text, using fallback string tokenization", e);
            return fallbackTokenize(text);
        }
    }

    /**
     * Fallback tokenization method using simple string tokenization
     */
    private List<Integer> fallbackTokenize(String text) {
        List<String> tokens = Tokenizer.simpleTokenize(text);
        List<Integer> result = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            result.add(Math.abs(token.hashCode()) % 10000);
        }
        return result;
    }

    /**
     * Estimates the token count for the given text.
     * Uses cache for improved performance on repeated calls.
     *
     * @param text The text to count tokens for
     * @return The estimated token count
     */
    public int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        return tokenCountCache.computeIfAbsent(text, this::calculateTokenCount);
    }

    /**
     * Calculates the token count for the given text without using cache.
     *
     * @param text The text to count tokens for
     * @return The token count
     */
    private int calculateTokenCount(String text) {
        if (!initialized || tokenizer == null) {
            LOG.warn("Tokenizer not initialized, using fallback token counting");
            return Tokenizer.simpleTokenize(text).size();
        }

        try {
            Encoding encoding = tokenizer.encode(text);
            return encoding.getIds().length;
        } catch (Exception e) {
            LOG.error("Error calculating token count, using fallback string tokenization", e);
            return Tokenizer.simpleTokenize(text).size();
        }
    }

    /**
     * Truncates the input text to fit within the specified token limit.
     *
     * @param text The text to truncate
     * @param maxTokens The maximum number of tokens allowed
     * @return The truncated text
     */
    public String truncateToTokenLimit(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }

        if (!initialized || tokenizer == null) {
            LOG.warn("Tokenizer not initialized, using fallback truncation");
            return fallbackTruncateToTokenLimit(text, maxTokens);
        }

        try {
            Encoding encoding = tokenizer.encode(text);
            long[] tokens = encoding.getIds();
            if (tokens.length <= maxTokens) {
                return text;
            }

            // Truncate the tokens array to maxTokens
            long[] truncatedTokens = Arrays.copyOf(tokens, maxTokens);
            return tokenizer.decode(truncatedTokens);
        } catch (Exception e) {
            LOG.error("Error truncating text, using fallback approach", e);
            return fallbackTruncateToTokenLimit(text, maxTokens);
        }
    }

    /**
     * Fallback method for truncating text by character approximation
     */
    private String fallbackTruncateToTokenLimit(String text, int maxTokens) {
        // Simple character-based approximation
        int approxCharsPerToken = 4;
        int estimatedChars = maxTokens * approxCharsPerToken;
        if (text.length() <= estimatedChars) {
            return text;
        }
        return text.substring(0, Math.min(text.length(), estimatedChars)) + "...";
    }

    /**
     * Gets the token IDs for the input text.
     *
     * @param text The input text
     * @return An array of token IDs
     */
    public long[] getTokenIds(String text) {
        if (!initialized || tokenizer == null) {
            LOG.warn("Tokenizer not initialized, using fallback token ID generation");
            List<String> stringTokens = Tokenizer.simpleTokenize(text);
            long[] result = new long[stringTokens.size()];
            for (int i = 0; i < stringTokens.size(); i++) {
                // Convert string tokens to token IDs (using hash code as a basic mapping)
                result[i] = Math.abs(stringTokens.get(i).hashCode() % 10000);
            }
            return result;
        }

        try {
            Encoding encoding = tokenizer.encode(text);
            return encoding.getIds();
        } catch (Exception e) {
            LOG.error("Error getting token IDs, using fallback approach", e);
            List<String> stringTokens = Tokenizer.simpleTokenize(text);
            long[] result = new long[stringTokens.size()];
            for (int i = 0; i < stringTokens.size(); i++) {
                // Convert string tokens to token IDs (using hash code as a basic mapping)
                result[i] = Math.abs(stringTokens.get(i).hashCode() % 10000);
            }
            return result;
        }
    }

    /**
     * Gets the attention mask for the input text.
     *
     * @param text The input text
     * @return An array representing the attention mask
     */
    public long[] getAttentionMask(String text) {
        if (!initialized || tokenizer == null) {
            LOG.warn("Tokenizer not initialized, using fallback attention mask");
            List<String> stringTokens = Tokenizer.simpleTokenize(text);
            long[] attentionMask = new long[stringTokens.size()];
            Arrays.fill(attentionMask, 1L);
            return attentionMask;
        }

        try {
            Encoding encoding = tokenizer.encode(text);
            return encoding.getAttentionMask();
        } catch (Exception e) {
            LOG.error("Error creating attention mask, using fallback approach", e);
            List<String> stringTokens = Tokenizer.simpleTokenize(text);
            long[] attentionMask = new long[stringTokens.size()];
            Arrays.fill(attentionMask, 1L);
            return attentionMask;
        }
    }

    /**
     * Converts token IDs to text.
     *
     * @param tokenIds The token IDs to decode
     * @return The decoded text
     */
    public String detokenize(long[] tokenIds) {
        if (!initialized || tokenizer == null) {
            LOG.warn("Tokenizer not initialized, cannot detokenize");
            return "";
        }

        try {
            return tokenizer.decode(tokenIds);
        } catch (Exception e) {
            LOG.error("Error detokenizing, returning empty string", e);
            return "";
        }
    }

    /**
     * Checks if the tokenizer is properly initialized
     */
    public boolean isInitialized() {
        return initialized && tokenizer != null;
    }

    /**
     * Gets the underlying HuggingFaceTokenizer.
     *
     * @return The HuggingFaceTokenizer instance
     */
    public HuggingFaceTokenizer getHuggingFaceTokenizer() {
        return tokenizer;
    }

    /**
     * Static method for backward compatibility.
     * Creates a temporary tokenizer to handle the call.
     */
    public static int getTokenCount(String text) {
        LOG.info("Static getTokenCount called - creating temporary tokenizer");
        BytePairEncodingTokenizer temp = new BytePairEncodingTokenizer();
        return temp.estimateTokenCount(text);
    }

    /**
     * Static method for backward compatibility.
     * Creates a temporary tokenizer to handle the call.
     */
    public static String truncateText(String text, int maxTokens) {
        LOG.info("Static truncateText called - creating temporary tokenizer");
        BytePairEncodingTokenizer temp = new BytePairEncodingTokenizer();
        return temp.truncateToTokenLimit(text, maxTokens);
    }
}
