/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.ai.tokenizer;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.Encoding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tokenizer implementation using Byte Pair Encoding (BPE) for accurate token calculations.
 * This implementation uses HuggingFace tokenizers for BPE implementation.
 */
@ApplicationScoped
public class BytePairEncodingTokenizer implements Tokenizer {
    private static final Logger LOG = Logger.getLogger(BytePairEncodingTokenizer.class);
    private static BytePairEncodingTokenizer instance;
    private final HuggingFaceTokenizer tokenizer;
    private final Map<String, Integer> tokenCountCache = new ConcurrentHashMap<>();

    /**
     * Constructor for the BytePairEncodingTokenizer.
     *
     * @param tokenizerPath The path to the HuggingFace tokenizer model
     */
    @Inject
    public BytePairEncodingTokenizer(
            @ConfigProperty(name = "ai.tokenizer.path", defaultValue = "./tokenizer") String tokenizerPath) {
        try {
            this.tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(tokenizerPath));
            LOG.info("BytePairEncodingTokenizer initialized with model from: " + tokenizerPath);
        } catch (Exception e) {
            LOG.error("Failed to initialize BytePairEncodingTokenizer", e);
            throw new RuntimeException("Failed to initialize tokenizer", e);
        }
        instance = this;
    }

    /**
     * Gets the singleton instance of the tokenizer.
     * Falls back to SimpleTokenizer if not initialized.
     *
     * @return The tokenizer instance
     */
    public static BytePairEncodingTokenizer getInstance() {
        if (instance == null) {
            LOG.warn("BytePairEncodingTokenizer not initialized, using fallback logic");
        }
        return instance;
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
            List<String> tokens = Tokenizer.tokenize(text);
            List<Integer> result = new ArrayList<>(tokens.size());
            for (String token : tokens) {
                result.add(Math.abs(token.hashCode()) % 10000);
            }
            return result;
        }
    }

    /**
     * Static method for compatibility with SimpleTokenizer's API.
     *
     * @param text The text to tokenize
     * @return A list of token IDs
     */
    public static List<Integer> getIntegerTokenIds(String text) {
        if (instance != null) {
            return instance.tokenize(text);
        } else {
            LOG.warn("BytePairEncodingTokenizer not initialized, using fallback approach");
            // Create simple token IDs from strings
            List<String> tokens = Tokenizer.tokenize(text);
            List<Integer> result = new ArrayList<>(tokens.size());
            for (String token : tokens) {
                result.add(Math.abs(token.hashCode()) % 10000);
            }
            return result;
        }
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
        try {
            Encoding encoding = tokenizer.encode(text);
            return encoding.getIds().length;
        } catch (Exception e) {
            LOG.error("Error calculating token count, using fallback string tokenization", e);
            return Tokenizer.tokenize(text).size();
        }
    }

    /**
     * Static method for estimating token count.
     *
     * @param text The text to count tokens for
     * @return The estimated token count
     */
    public static int getTokenCount(String text) {
        if (instance != null) {
            return instance.estimateTokenCount(text);
        } else {
            LOG.warn("BytePairEncodingTokenizer not initialized, using fallback string tokenization");
            return Tokenizer.tokenize(text).size();
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

            // Fallback: simple character-based approximation
            int approxCharsPerToken = 4;
            int estimatedChars = maxTokens * approxCharsPerToken;
            if (text.length() <= estimatedChars) {
                return text;
            }
            return text.substring(0, Math.min(text.length(), estimatedChars)) + "...";
        }
    }

    /**
     * Static method for truncating text to token limit.
     *
     * @param text The text to truncate
     * @param maxTokens The maximum number of tokens allowed
     * @return The truncated text
     */
    public static String truncateText(String text, int maxTokens) {
        if (instance != null) {
            return instance.truncateToTokenLimit(text, maxTokens);
        } else {
            LOG.warn("BytePairEncodingTokenizer not initialized, using fallback approach");
            // Simple character-based approximation
            int approxCharsPerToken = 4;
            int estimatedChars = maxTokens * approxCharsPerToken;
            if (text.length() <= estimatedChars) {
                return text;
            }
            return text.substring(0, Math.min(text.length(), estimatedChars)) + "...";
        }
    }

    /**
     * Gets the token IDs for the input text.
     *
     * @param text The input text
     * @return An array of token IDs
     */
    public long[] getTokenIds(String text) {
        try {
            Encoding encoding = tokenizer.encode(text);
            return encoding.getIds();
        } catch (Exception e) {
            LOG.error("Error getting token IDs, using fallback approach", e);
            List<String> stringTokens = Tokenizer.tokenize(text);
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
        try {
            Encoding encoding = tokenizer.encode(text);
            return encoding.getAttentionMask();
        } catch (Exception e) {
            LOG.error("Error creating attention mask, using fallback approach", e);
            List<String> stringTokens = Tokenizer.tokenize(text);
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
        try {
            return tokenizer.decode(tokenIds);
        } catch (Exception e) {
            LOG.error("Error detokenizing, returning empty string", e);
            return "";
        }
    }

    /**
     * Gets the underlying HuggingFaceTokenizer.
     *
     * @return The HuggingFaceTokenizer instance
     */
    public HuggingFaceTokenizer getHuggingFaceTokenizer() {
        return tokenizer;
    }
}
